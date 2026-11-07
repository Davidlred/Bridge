package app.thebridge.data.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.thebridge.MainActivity
import app.thebridge.R
import app.thebridge.data.AlertChannels
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** A reminder Arc set: [id] is the coach reply it came from, [at] local wall time ("2026-11-07T17:00"). */
@Serializable
data class ArcReminder(val id: String, val at: String, val text: String, val call: String? = null) {
    fun time(): LocalDateTime? = runCatching { LocalDateTime.parse(at) }.getOrNull()
}

/**
 * Reminders and alarms set by asking Arc. They live on this phone only (the coach reply that set them syncs, so another
 * phone can set the same one with a tap). When the user allows exact alarms they ring on the minute through the
 * system alarm clock; otherwise Android may deliver them a few minutes late.
 */
@Singleton
class ArcReminders @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val store: DataStore<Preferences>,
) {
    private val alarms = ctx.getSystemService(AlarmManager::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val list = ListSerializer(ArcReminder.serializer())

    val all: Flow<List<ArcReminder>> = store.data.map { p -> p[KEY]?.let { runCatching { json.decodeFromString(list, it) }.getOrNull() }.orEmpty() }

    /** On the minute (true), or possibly a few minutes late until the user allows exact alarms. */
    fun exact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
    fun notificationsOn(): Boolean = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    suspend fun set(r: ArcReminder) {
        save(all.first().filterNot { it.id == r.id } + r)
        schedule(r)
    }

    suspend fun cancel(id: String) {
        save(all.first().filterNot { it.id == id })
        alarms.cancel(fireIntent(id))
        NotificationManagerCompat.from(ctx).cancel(notifId(id))
    }

    /** The alarm went off: it is no longer pending. */
    suspend fun fired(id: String): ArcReminder? {
        val r = all.first().find { it.id == id } ?: return null
        save(all.first().filterNot { it.id == id })
        return r
    }

    /** After a reboot, a clock change, or permission to ring on the minute: lay every reminder out again. Missed ones ring now. */
    suspend fun rescheduleAll() {
        val now = LocalDateTime.now()
        all.first().forEach { r ->
            val t = r.time() ?: return@forEach
            if (t.isAfter(now)) schedule(r) else { fired(r.id); show(r) }
        }
    }

    private fun schedule(r: ArcReminder) {
        val ms = (r.time() ?: return).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val fire = fireIntent(r.id)
        if (exact()) {
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(ms, open), fire)
        } else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, fire)
    }

    /** The alarm itself: the reminder text, a "Call" button when it is about calling someone, and Snooze. */
    fun show(r: ArcReminder) {
        if (!notificationsOn()) return
        val open = PendingIntent.getActivity(ctx, notifId(r.id), Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val snooze = PendingIntent.getBroadcast(ctx, notifId(r.id) + 1, Intent(ctx, ArcAlarmReceiver::class.java).setAction(ACTION_SNOOZE)
            .putExtra(EXTRA_ID, r.id).putExtra(EXTRA_TEXT, r.text).putExtra(EXTRA_CALL, r.call), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, AlertChannels.ARC_ALARMS).setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(r.text).setContentText(ctx.getString(R.string.arc_alarm_body, r.time()?.format(DateTimeFormatter.ofPattern("h:mm a")).orEmpty()))
            .setContentIntent(open).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_MAX)
            .apply {
                r.call?.let { name ->
                    val call = PendingIntent.getActivity(ctx, notifId(r.id) + 2, Intent(ctx, MainActivity::class.java).setAction(ACTION_CALL).putExtra(EXTRA_CALL, name)
                        .putExtra(EXTRA_ID, r.id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    addAction(R.drawable.ic_call, ctx.getString(R.string.arc_call_name, name), call)
                }
            }
            .addAction(R.drawable.ic_alarm, ctx.getString(R.string.arc_snooze), snooze).build()
        runCatching { NotificationManagerCompat.from(ctx).notify(notifId(r.id), n) }
    }

    fun dismissAlarm(id: String) = NotificationManagerCompat.from(ctx).cancel(notifId(id))

    private suspend fun save(rs: List<ArcReminder>) { store.edit { it[KEY] = json.encodeToString(list, rs.sortedBy { r -> r.at }) } }
    private fun fireIntent(id: String) = PendingIntent.getBroadcast(ctx, notifId(id), Intent(ctx, ArcAlarmReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun notifId(id: String) = 40_000 + (id.hashCode() and 0xFFFF) * 4

    companion object {
        const val ACTION_FIRE = "app.thebridge.arc.FIRE"
        const val ACTION_SNOOZE = "app.thebridge.arc.SNOOZE"
        /** Opens the app and starts the call sheet for [EXTRA_CALL]. */
        const val ACTION_CALL = "app.thebridge.arc.CALL"
        const val EXTRA_ID = "id"; const val EXTRA_TEXT = "text"; const val EXTRA_CALL = "call"
        const val SNOOZE_MINUTES = 10L
        private val KEY = stringPreferencesKey("arc_reminders")
    }
}

@AndroidEntryPoint
class ArcAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var arc: ArcReminders

    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val id = intent.getStringExtra(ArcReminders.EXTRA_ID) ?: return@launch
                when (intent.action) {
                    ArcReminders.ACTION_FIRE -> arc.fired(id)?.let { arc.show(it) }
                    ArcReminders.ACTION_SNOOZE -> {
                        arc.dismissAlarm(id)
                        val at = LocalDateTime.now().plusMinutes(ArcReminders.SNOOZE_MINUTES).withSecond(0).withNano(0)
                        arc.set(ArcReminder(id, at.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).take(16), intent.getStringExtra(ArcReminders.EXTRA_TEXT).orEmpty(), intent.getStringExtra(ArcReminders.EXTRA_CALL)))
                    }
                }
            } finally {
                result.finish()
            }
        }
    }
}
