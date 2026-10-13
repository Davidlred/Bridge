package app.thebridge.data.session

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.thebridge.BuildConfig
import app.thebridge.data.local.BridgeDb
import app.thebridge.data.remote.ApiClient
import app.thebridge.data.remote.AuthResponse
import app.thebridge.data.remote.UserDto
import app.thebridge.data.sync.SyncWorker
import app.thebridge.util.UuidV7
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SessionPhase {
    data object Launching : SessionPhase
    data object SignedOut : SessionPhase
    /** Signed in and usable (goal onboarding steps arrive with milestone 2). */
    data object Ready : SessionPhase
    /** Refresh token rejected. The app keeps working on local data; a sheet asks the user to log in again. */
    data object Expired : SessionPhase
}

@Singleton
class SessionManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val vault: TokenVault,
    private val api: ApiClient,
    private val db: BridgeDb,
    private val store: DataStore<Preferences>,
) {
    private val _phase = MutableStateFlow<SessionPhase>(SessionPhase.Launching)
    val phase = _phase.asStateFlow()
    private val _user = MutableStateFlow<UserDto?>(null)
    val user = _user.asStateFlow()

    /** Reads local state only; never waits on the network. */
    suspend fun bootstrap() {
        if (_phase.value != SessionPhase.Launching) return
        val prefs = store.data.first()
        val saved = prefs[USER]?.let { runCatching { api.json.decodeFromString<UserDto>(it) }.getOrNull() }
        val tokens = withContext(Dispatchers.IO) { vault.read() }
        _user.value = saved
        _phase.value = when {
            saved == null -> SessionPhase.SignedOut
            tokens == null -> SessionPhase.Expired          // tokens lost but local data is still this user's
            else -> SessionPhase.Ready
        }
        if (_phase.value == SessionPhase.Ready) SyncWorker.kick(ctx)
    }

    suspend fun deviceId(): String = store.data.first()[DEVICE] ?: UuidV7.next().also { id -> store.edit { it[DEVICE] = id } }

    suspend fun signedIn(r: AuthResponse) {
        // Unsynced work belongs to whoever made it: only a different account starts from an empty database.
        if (_user.value?.id != r.user.id) { withContext(Dispatchers.IO) { db.clearAllTables() }; store.edit { it.remove(CURSOR) } }
        withContext(Dispatchers.IO) { vault.write(Tokens(r.accessToken, r.refreshToken)) }
        store.edit { it[USER] = api.json.encodeToString(UserDto.serializer(), r.user) }
        _user.value = r.user
        _phase.value = SessionPhase.Ready
        SyncWorker.kick(ctx)
    }

    fun expired() { if (_phase.value == SessionPhase.Ready) _phase.value = SessionPhase.Expired }

    suspend fun logOut() {
        runCatching { api.logOut() }             // best effort; the refresh token is dropped locally either way
        clearLocal()
    }

    /** Throws when offline or the server refuses, so the UI can keep the account and say why. */
    suspend fun deleteAccount() {
        api.deleteAccount()
        clearLocal()
    }

    private suspend fun clearLocal() {
        SyncWorker.cancel(ctx)
        withContext(Dispatchers.IO) { vault.write(null); db.clearAllTables() }
        store.edit { it.remove(USER); it.remove(CURSOR); it.remove(VERIFIER) }
        _user.value = null
        _phase.value = SessionPhase.SignedOut
    }

    suspend fun cursor(): Long = store.data.first()[CURSOR] ?: 0
    suspend fun setCursor(value: Long) { store.edit { it[CURSOR] = value } }

    // --- Sign in with Apple (browser tab, PKCE-bound hand-off) ---
    suspend fun appleStartUri(): Uri {
        val verifier = ByteArray(32).also(SecureRandom()::nextBytes).b64()
        store.edit { it[VERIFIER] = verifier }
        val challenge = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()).b64()
        return Uri.parse("${BuildConfig.API_BASE}/v1/auth/apple/web/start?challenge=$challenge")
    }

    suspend fun appleVerifier(): String? = store.data.first()[VERIFIER]

    private fun ByteArray.b64() = Base64.encodeToString(this, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private companion object {
        val USER = stringPreferencesKey("user"); val DEVICE = stringPreferencesKey("device_id")
        val CURSOR = longPreferencesKey("sync_cursor"); val VERIFIER = stringPreferencesKey("apple_verifier")
    }
}
