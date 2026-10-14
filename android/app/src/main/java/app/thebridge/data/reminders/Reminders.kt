package app.thebridge.data.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.thebridge.MainActivity
import app.thebridge.R
import app.thebridge.data.local.BridgeDb
import app.thebridge.data.local.LocalDay
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Reminder times chosen in Profile → Notifications. null = off. */
data class ReminderSettings(val daily: LocalTime? = LocalTime.of(8, 0), val streakRisk: LocalTime? = LocalTime.of(20, 0))

object TaskTime {
    private val clock = Regex("""^\s*(\d{1,2})(?::(\d{2}))?\s*([ap])\.?\s*m\.?\s*$""", RegexOption.IGNORE_CASE)
    private val h24 = Regex("""^\s*(\d{1,2}):(\d{2})\s*$""")

    /** "7:00 AM", "7am", "19:30", "07:00" → a time; "After run", "Evening" → null (no reminder). */
    fun parse(text: String?): LocalTime? {
        if (text.isNullOrBlank()) return null
        h24.matchEntire(text)?.let { m -> val h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt(); return if (h in 0..23 && min in 0..59) LocalTime.of(h, min) else null }
        clock.matchEntire(text)?.let { m ->
            var h = m.groupValues[1].toInt(); val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            if (h !in 1..12 || min !in 0..59) return null
            if (m.groupValues[3].equals("p", true) && h != 12) h += 12
            if (m.groupValues[3].equals("a", true) && h == 12) h = 0
            return LocalTime.of(h, min)
        }
        return null
    }
}

/**
 * Local reminders, scheduled on the device so they work offline. Only the next 24 hours are scheduled;
 * a 03:05 alarm (and every change to the plan) schedules the next batch. Wall-clock times, so DST shifts don't move them.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val db: BridgeDb,
    private val store: DataStore<Preferences>,
) {
    private val alarms = ctx.getSystemService(AlarmManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings: Flow<ReminderSettings> = store.data.map { p ->
        ReminderSettings(daily = p[DAILY].toTime(LocalTime.of(8, 0)), streakRisk = p[RISK].toTime(LocalTime.of(20, 0)))
    }
    private fun String?.toTime(default: LocalTime): LocalTime? = when (this) { null -> default; "off" -> null; else -> runCatching { LocalTime.parse(this) }.getOrDefault(default) }

    suspend fun setDaily(t: LocalTime?) { store.edit { it[DAILY] = t?.toString() ?: "off" }; reschedule() }
    suspend fun setStreakRisk(t: LocalTime?) { store.edit { it[RISK] = t?.toString() ?: "off" }; reschedule() }

    fun createChannel() {
        val ch = NotificationChannel(CHANNEL, ctx.getString(R.string.notif_channel_reminders), NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = ctx.getString(R.string.notif_channel_reminders_desc) }
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    fun rescheduleAsync() { scope.launch { reschedule() } }

    suspend fun reschedule() {
        val now = LocalDateTime.now()
        val s = settings.first()
        // Cancel everything we scheduled before, then lay out the next 24 hours.
        val previous = store.data.first()[SCHEDULED].orEmpty().split(',').filter(String::isNotBlank)
        previous.forEach { alarms.cancel(pending(Intent(ctx, ReminderReceiver::class.java).setAction(it), it.hashCode())) }
        val ids = mutableListOf<String>()
        fun at(time: LocalTime, action: String, extras: Intent.() -> Unit = {}) {
            var whenAt = now.toLocalDate().atTime(time)
            if (!whenAt.isAfter(now)) whenAt = whenAt.plusDays(1)
            val intent = Intent(ctx, ReminderReceiver::class.java).setAction(action).apply(extras)
            val ms = whenAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pending(intent, action.hashCode()))
            ids += action
        }
        val hasGoals = db.goals().activeCount() > 0
        if (hasGoals) {
            s.daily?.let { at(it, ACTION_DAILY) }
            s.streakRisk?.let { at(it, ACTION_RISK) }
            db.goals().activeTasks().first().filter { it.deletedAt == null }.forEach { t ->
                TaskTime.parse(t.timeOfDay)?.let { time -> at(time, "$ACTION_TASK:${t.id}") { putExtra(EXTRA_TITLE, t.title); putExtra(EXTRA_TIME, t.timeOfDay) } }
            }
        }
        at(LocalTime.of(3, 5), ACTION_RESCHEDULE)
        store.edit { it[SCHEDULED] = ids.joinToString(",") }
    }

    private fun pending(intent: Intent, code: Int) = PendingIntent.getBroadcast(ctx, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** True when at least one task (any goal) is done for the current local day. */
    suspend fun anyDoneToday(): Boolean = db.goals().activeCompletions().first().any { it.localDate == LocalDay.today() }
    suspend fun taskDoneToday(id: String): Boolean = db.goals().activeCompletions().first().any { it.taskId == id && it.localDate == LocalDay.today() }

    fun notify(id: Int, title: String, body: String?) {
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle(title)
            .apply { if (body != null) setContentText(body) }.setContentIntent(open).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }   // SecurityException if permission was revoked meanwhile
    }

    companion object {
        const val CHANNEL = "reminders"
        const val ACTION_DAILY = "app.thebridge.reminder.DAILY"
        const val ACTION_RISK = "app.thebridge.reminder.RISK"
        const val ACTION_TASK = "app.thebridge.reminder.TASK"
        const val ACTION_RESCHEDULE = "app.thebridge.reminder.RESCHEDULE"
        const val EXTRA_TITLE = "title"; const val EXTRA_TIME = "time"
        private val DAILY = stringPreferencesKey("reminder_daily")
        private val RISK = stringPreferencesKey("reminder_risk")
        private val SCHEDULED = stringPreferencesKey("reminder_scheduled")
    }
}

@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {
    @Inject lateinit var scheduler: ReminderScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val action = intent.action.orEmpty()
                when {
                    action == ReminderScheduler.ACTION_DAILY -> scheduler.notify(1, context.getString(R.string.notif_daily), null)
                    // Only nudge when nothing is done yet today.
                    action == ReminderScheduler.ACTION_RISK -> if (!scheduler.anyDoneToday()) scheduler.notify(2, context.getString(R.string.notif_risk), null)
                    action.startsWith(ReminderScheduler.ACTION_TASK) -> {
                        val id = action.substringAfter(':')
                        if (!scheduler.taskDoneToday(id)) scheduler.notify(id.hashCode(), intent.getStringExtra(ReminderScheduler.EXTRA_TITLE).orEmpty(),
                            context.getString(R.string.notif_task_body, intent.getStringExtra(ReminderScheduler.EXTRA_TIME).orEmpty()))
                    }
                }
                // Boot, time-zone change, the nightly alarm, or any fired reminder: lay out the next 24 hours.
                scheduler.reschedule()
            } finally {
                result.finish()
            }
        }
    }
}
