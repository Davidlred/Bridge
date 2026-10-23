package app.thebridge.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import app.thebridge.R

/**
 * Reminders and updates pop up on screen, play the notification sound, vibrate in a strong pattern and blink the
 * notification light on phones that have one. Android fixes a channel's sound and vibration once it exists, so these
 * louder settings use new channel ids and the old quiet channels are deleted.
 */
object AlertChannels {
    const val REMINDERS = "reminders_alert"
    const val UPDATES = "updates_alert"
    private val OLD = listOf("reminders", "updates")
    /** Three long buzzes: hard to miss in a pocket. */
    val VIBRATION = longArrayOf(0, 600, 250, 600, 250, 600)
    private const val LIGHT = 0xFFE8643A.toInt()

    fun create(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        OLD.forEach { nm.deleteNotificationChannel(it) }
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audio = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        fun channel(id: String, name: Int, description: Int) = NotificationChannel(id, ctx.getString(name), NotificationManager.IMPORTANCE_HIGH).apply {
            this.description = ctx.getString(description)
            setSound(sound, audio)
            enableVibration(true); vibrationPattern = VIBRATION
            enableLights(true); lightColor = LIGHT
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        nm.createNotificationChannels(listOf(
            channel(REMINDERS, R.string.notif_channel_reminders, R.string.notif_channel_reminders_desc),
            channel(UPDATES, R.string.notif_channel_updates, R.string.notif_channel_updates_desc),
        ))
    }
}
