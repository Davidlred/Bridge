package app.thebridge.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.thebridge.MainActivity
import app.thebridge.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Server pushes (mentions, nudges, game night, DMs). In the background Android shows them itself on the "updates" channel;
 * in the foreground they arrive here. Push bodies never carry goal, task, chat or DM text (docs/ARCHITECTURE.md).
 */
@AndroidEntryPoint
class PushService : FirebaseMessagingService() {
    @Inject lateinit var social: SocialRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** A new token (first run, reinstall, restore): tell the server, if someone is signed in. */
    override fun onNewToken(token: String) { scope.launch { social.registerDevice() } }

    override fun onMessageReceived(message: RemoteMessage) {
        val n = message.notification ?: return
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val built = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(n.title).setContentText(n.body).setContentIntent(open).setAutoCancel(true).build()
        // One notification per collapse key, so a repeated nudge replaces the previous one.
        val id = (message.collapseKey ?: message.messageId ?: n.title.orEmpty()).hashCode()
        runCatching { NotificationManagerCompat.from(this).notify(id, built) }   // SecurityException if permission was revoked meanwhile
    }

    companion object {
        const val CHANNEL = "updates"
        fun createChannel(ctx: Context) {
            val ch = NotificationChannel(CHANNEL, ctx.getString(R.string.notif_channel_updates), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = ctx.getString(R.string.notif_channel_updates_desc) }
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }
}
