package app.thebridge.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Deferred invite link: someone without the app opens thebridge.app/join/CODE, installs from Google Play, and the store
 * hands the code back to the app ("referrer=join=CODE"). Read once per install, the first time the signed-in app opens.
 */
@Singleton
class InviteReferrer @Inject constructor(@ApplicationContext private val ctx: Context, private val store: DataStore<Preferences>) {
    suspend fun takeCode(): String? {
        if (store.data.first()[CHECKED] == true) return null
        val raw = runCatching { read() }.getOrNull()
        store.edit { it[CHECKED] = true }
        return parse(raw)
    }

    private suspend fun read(): String? = suspendCancellableCoroutine { cont ->
        val client = InstallReferrerClient.newBuilder(ctx).build()
        client.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(code: Int) {
                val referrer = if (code == InstallReferrerClient.InstallReferrerResponse.OK) runCatching { client.installReferrer.installReferrer }.getOrNull() else null
                runCatching { client.endConnection() }
                if (cont.isActive) cont.resume(referrer)
            }
            override fun onInstallReferrerServiceDisconnected() { if (cont.isActive) cont.resume(null) }
        })
        cont.invokeOnCancellation { runCatching { client.endConnection() } }
    }

    companion object {
        private val CHECKED = booleanPreferencesKey("install_referrer_checked")
        private val CODE = Regex("^[A-Z]{4}-\\d{4}$")

        /** "join=CALC-2026" (possibly URL-encoded, possibly among other utm_ parameters) → "CALC-2026". */
        fun parse(referrer: String?): String? {
            if (referrer.isNullOrBlank()) return null
            return java.net.URLDecoder.decode(referrer, "UTF-8").split('&').firstNotNullOfOrNull { kv -> kv.takeIf { it.startsWith("join=") }?.removePrefix("join=") }
                ?.trim()?.uppercase()?.takeIf(CODE::matches)
        }
    }
}
