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
) : ViewModel() {
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
        // Network regained: push what was queued and say so.
        viewModelScope.launch {
            online.drop(1).collect { isOnline ->
                if (!isOnline) return@collect
                val n = queued.value
                SyncWorker.kick(ctx)
                toaster.show(if (n > 0) UiText.Plural(R.plurals.back_online_synced, n) else UiText.Res(R.string.back_online), ToastKind.Ok)
            }
        }
    }

    fun logOut() = viewModelScope.launch {
        session.logOut()
        toaster.show(UiText.Res(R.string.toast_logged_out))
    }

    fun clearDeleteError() { _deleteError.value = null }

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
