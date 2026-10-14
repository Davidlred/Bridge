package app.thebridge.data.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SessionPhase {
    data object Launching : SessionPhase
    data object SignedOut : SessionPhase
    /** Signed in, first goal not set up yet: the onboarding steps are shown instead of the tabs. Survives app restarts. */
    data object Onboarding : SessionPhase
    /** Signed in with at least one goal. */
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
    private val reminders: app.thebridge.data.reminders.ReminderScheduler,
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
            prefs[ONBOARDING] == true -> SessionPhase.Onboarding
            else -> SessionPhase.Ready
        }
        if (_phase.value != SessionPhase.SignedOut) SyncWorker.kick(ctx)
    }

    suspend fun deviceId(): String = store.data.first()[DEVICE] ?: UuidV7.next().also { id -> store.edit { it[DEVICE] = id } }

    suspend fun signedIn(r: AuthResponse) {
        // Unsynced work belongs to whoever made it: only a different account starts from an empty database.
        if (_user.value?.id != r.user.id) { withContext(Dispatchers.IO) { db.clearAllTables() }; store.edit { it.remove(CURSOR) } }
        withContext(Dispatchers.IO) { vault.write(Tokens(r.accessToken, r.refreshToken)) }
        store.edit { it[USER] = api.json.encodeToString(UserDto.serializer(), r.user); if (r.isNew) it[ONBOARDING] = true }
        _user.value = r.user
        _phase.value = if (r.isNew || store.data.first()[ONBOARDING] == true) SessionPhase.Onboarding else SessionPhase.Ready
        SyncWorker.kick(ctx)
    }

    fun expired() { if (_phase.value == SessionPhase.Ready || _phase.value == SessionPhase.Onboarding) _phase.value = SessionPhase.Expired }

    /** First goal created: show the tabs from now on. */
    suspend fun onboardingDone() {
        store.edit { it.remove(ONBOARDING) }
        if (_phase.value == SessionPhase.Onboarding) _phase.value = SessionPhase.Ready
    }

    /** Re-reads the profile (photo link, name) after it changes on the server. */
    suspend fun refreshUser() {
        val fresh = runCatching { api.me() }.getOrNull() ?: return
        store.edit { it[USER] = api.json.encodeToString(UserDto.serializer(), fresh) }
        _user.value = fresh
    }

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
        store.edit { it.remove(USER); it.remove(CURSOR); it.remove(ONBOARDING) }
        reminders.reschedule()                                   // no goals left: only the nightly re-arm remains
        _user.value = null
        _phase.value = SessionPhase.SignedOut
    }

    suspend fun cursor(): Long = store.data.first()[CURSOR] ?: 0
    suspend fun setCursor(value: Long) { store.edit { it[CURSOR] = value } }

    private companion object {
        val USER = stringPreferencesKey("user"); val DEVICE = stringPreferencesKey("device_id")
        val CURSOR = longPreferencesKey("sync_cursor")
        val ONBOARDING = androidx.datastore.preferences.core.booleanPreferencesKey("onboarding")
    }
}
