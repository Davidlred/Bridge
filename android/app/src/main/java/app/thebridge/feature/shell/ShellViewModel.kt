package app.thebridge.feature.shell

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.thebridge.R
import app.thebridge.data.NetworkMonitor
import app.thebridge.data.local.BridgeDb
import app.thebridge.data.remote.ApiClient
import app.thebridge.data.remote.OfflineException
import app.thebridge.data.remote.UnauthorizedException
import app.thebridge.data.session.SessionManager
import app.thebridge.data.sync.SyncWorker
import app.thebridge.ui.Toaster
import app.thebridge.ui.components.ToastKind
import app.thebridge.ui.components.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** State shared by every signed-in screen: who the user is, connectivity, unsynced changes, account actions. */
@HiltViewModel
class ShellViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val session: SessionManager,
    private val toaster: Toaster,
    network: NetworkMonitor,
    db: BridgeDb,
    api: ApiClient,
    private val reminderScheduler: app.thebridge.data.reminders.ReminderScheduler,
    private val ai: app.thebridge.data.AiRepository,
    private val goals: app.thebridge.data.GoalRepository,
    private val store: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
    private val api2: ApiClient,
    private val social: app.thebridge.data.SocialRepository,
    private val billing: app.thebridge.data.BillingRepository,
) : ViewModel() {
    private companion object { val COACH_READS_JOURNAL = androidx.datastore.preferences.core.booleanPreferencesKey("coach_reads_journal") }
    val reminders = reminderScheduler.settings
    val share = social.share
    val coachReadsJournal = store.data.map { it[COACH_READS_JOURNAL] == true }
    val phase = session.phase
    val user = session.user
    val online = network.online
    val queued = db.outbox().count().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val toasts = toaster.toasts
    val upgradeRequired = api.upgradeRequired

    private val _deleting = MutableStateFlow(false)
    val deleting = _deleting.asStateFlow()
    private val _deleteError = MutableStateFlow<UiText?>(null)
    val deleteError = _deleteError.asStateFlow()

    init {
        viewModelScope.launch { session.bootstrap() }
        // Signed in: register this install (push token once Firebase is set up, and the home time zone).
        viewModelScope.launch {
            phase.collect {
                if (it == app.thebridge.data.session.SessionPhase.Ready) { social.registerDevice(); session.user.value?.id?.let { id -> billing.identify(id) } }
                if (it == app.thebridge.data.session.SessionPhase.SignedOut) billing.signedOut()
            }
        }
        // Network regained: push what was queued and say so.
        viewModelScope.launch {
            online.drop(1).collect { isOnline ->
                if (!isOnline) return@collect
                val n = queued.value
                SyncWorker.kick(ctx)
                launch { social.flushQueued() }                         // alliance chat written offline
                toaster.show(if (n > 0) UiText.Plural(R.plurals.back_online_synced, n) else UiText.Res(R.string.back_online), ToastKind.Ok)
            }
        }
    }

    fun logOut() = viewModelScope.launch {
        session.logOut()
        toaster.show(UiText.Res(R.string.toast_logged_out))
    }

    fun clearDeleteError() { _deleteError.value = null }

    /** "Download my data": fetches the export and writes it where the user chose in the system Save dialog. */
    fun exportData(target: android.net.Uri) = viewModelScope.launch {
        try {
            val json = api2.exportData()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ctx.contentResolver.openOutputStream(target)?.use { it.write(json.toByteArray()) } }
            toaster.show(UiText.Res(R.string.toast_exported), ToastKind.Ok)
        } catch (e: OfflineException) { toaster.show(UiText.Res(R.string.error_offline), ToastKind.Warn) }
        catch (e: Exception) { toaster.show(UiText.Res(R.string.error_generic), ToastKind.Warn) }
    }

    /** Profile → Privacy: Nothing → Streaks only → Streaks & milestones. */
    fun cycleShare() = viewModelScope.launch { social.setShare(share.first().next()) }

    /** For invite links: they open Community inside the user's first active goal. */
    suspend fun firstGoalId(): String? = goals.activeGoals().first().firstOrNull()?.goal?.id
    fun needGoalFirst() = toaster.show(UiText.Res(R.string.join_need_goal), ToastKind.Info)

    // Reminder times cycle through a few sensible choices, as in the prototype; "off" is the last stop.
    private fun next(current: java.time.LocalTime?, options: List<Int>): java.time.LocalTime? {
        val all = options.map { java.time.LocalTime.of(it, 0) } + listOf<java.time.LocalTime?>(null)
        return all[(all.indexOf(current) + 1).mod(all.size)]
    }
    fun cycleDaily() = viewModelScope.launch { reminderScheduler.setDaily(next(reminders.first().daily, listOf(7, 8, 9))) }
    fun cycleRisk() = viewModelScope.launch { reminderScheduler.setStreakRisk(next(reminders.first().streakRisk, listOf(19, 20, 21))) }

    /** Empty or offensive names come back as "Arc" from the server. */
    fun renameCoach(name: String) = viewModelScope.launch {
        try { api2.updateMe(coachName = name); session.refreshUser() }
        catch (e: OfflineException) { toaster.show(UiText.Res(R.string.error_offline), ToastKind.Warn) }
        catch (e: Exception) { toaster.show(UiText.Res(R.string.error_generic), ToastKind.Warn) }
    }

    /** "Let my coach read my journal": stored locally for the switch and synced so the server knows. */
    fun setCoachReadsJournal(on: Boolean) = viewModelScope.launch {
        store.edit { it[COACH_READS_JOURNAL] = on }
        session.user.value?.id?.let { goals.saveSetting(it, "coachReadsJournal", on) }
    }

    /** Deletes the photo and every image generated from it (server side), then refreshes the profile. */
    fun removePhoto() = viewModelScope.launch {
        try { ai.deletePhoto(); session.refreshUser(); toaster.show(UiText.Res(R.string.toast_photo_removed), ToastKind.Ok) }
        catch (e: OfflineException) { toaster.show(UiText.Res(R.string.error_offline), ToastKind.Warn) }
        catch (e: Exception) { toaster.show(UiText.Res(R.string.error_generic), ToastKind.Warn) }
    }

    fun deleteAccount() {
        if (_deleting.value) return
        _deleting.value = true; _deleteError.value = null
        viewModelScope.launch {
            try {
                session.deleteAccount()
                toaster.show(UiText.Res(R.string.toast_account_deleted))
            } catch (e: OfflineException) {
                _deleteError.value = UiText.Res(R.string.delete_offline)
            } catch (e: UnauthorizedException) {
                session.expired()                                   // must prove who they are before deleting
                _deleteError.value = UiText.Res(R.string.session_expired_title)
            } catch (e: Exception) {
                _deleteError.value = UiText.Res(R.string.error_generic)
            } finally {
                _deleting.value = false
            }
        }
    }
}
