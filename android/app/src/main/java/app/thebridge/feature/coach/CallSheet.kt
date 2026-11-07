package app.thebridge.feature.coach

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.thebridge.R
import app.thebridge.data.phone.ArcCalls
import app.thebridge.data.phone.CallTarget
import app.thebridge.data.phone.PhoneContact
import app.thebridge.data.phone.PhoneContacts
import app.thebridge.ui.components.BridgeSheet
import app.thebridge.ui.components.PrimaryButton
import app.thebridge.ui.components.SecondaryButton
import app.thebridge.ui.components.SheetTitle
import app.thebridge.ui.theme.BType
import app.thebridge.ui.theme.LocalBridgeColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Each step of "Arc, call Sharon". */
sealed interface CallUi {
    /** First time: Arc explains, then asks for contacts (and calls). [denied]: the user said no, so only Settings can turn it on. */
    data class NeedsContacts(val name: String, val denied: Boolean = false) : CallUi
    data class Searching(val name: String) : CallUi
    data class NotFound(val name: String) : CallUi
    data class Pick(val name: String, val options: List<PhoneContact>) : CallUi
    /**
     * A few seconds to cancel (a misheard name shouldn't ring someone), then the call.
     * [asking]: waiting for the "make phone calls" permission. [direct]: rings straight away; otherwise the dialer opens with the number.
     */
    data class Ringing(val contact: PhoneContact, val seconds: Int, val direct: Boolean, val asking: Boolean = false) : CallUi
}

@HiltViewModel
class CallViewModel @Inject constructor(private val calls: ArcCalls, private val contacts: PhoneContacts) : ViewModel() {
    private val _ui = MutableStateFlow<CallUi?>(null)
    val ui: StateFlow<CallUi?> = _ui.asStateFlow()
    private var countdown: Job? = null

    init { viewModelScope.launch { calls.request.collect { t -> if (t != null) start(t) } } }

    private fun start(t: CallTarget) {
        countdown?.cancel()
        when (t) {
            is CallTarget.Number -> ring(PhoneContact(t.number, t.number))
            is CallTarget.Name -> if (contacts.canRead()) search(t.name) else _ui.value = CallUi.NeedsContacts(t.name)
        }
    }

    private fun search(name: String) = viewModelScope.launch {
        _ui.value = CallUi.Searching(name)
        val found = contacts.find(name)
        when (found.size) {
            0 -> _ui.value = CallUi.NotFound(name)
            1 -> ring(found.single())
            else -> _ui.value = CallUi.Pick(name, found.take(6))
        }
    }

    fun contactsResult(granted: Boolean) {
        val s = _ui.value as? CallUi.NeedsContacts ?: return
        if (granted) search(s.name) else _ui.value = s.copy(denied = true)
    }

    fun pick(c: PhoneContact) = ring(c)

    private fun ring(c: PhoneContact) {
        _ui.value = CallUi.Ringing(c, SECONDS, direct = contacts.canCall(), asking = !contacts.canCall())
        if (contacts.canCall()) count()
    }

    /** The "make phone calls" prompt was answered (either way: without it the dialer opens instead). */
    fun callPermissionDone() {
        val r = _ui.value as? CallUi.Ringing ?: return
        _ui.value = r.copy(asking = false, direct = contacts.canCall())
        count()
    }

    private fun count() {
        countdown?.cancel()
        countdown = viewModelScope.launch {
            while (true) {
                val r = _ui.value as? CallUi.Ringing ?: return@launch
                if (r.seconds <= 0) break
                delay(1_000)
                _ui.value = (_ui.value as? CallUi.Ringing)?.let { it.copy(seconds = it.seconds - 1) } ?: return@launch
            }
            callNow()
        }
    }

    fun callNow() {
        countdown?.cancel()
        val r = _ui.value as? CallUi.Ringing ?: return
        contacts.call(r.contact.number)
        close()
    }

    fun close() { countdown?.cancel(); _ui.value = null; calls.done() }

    companion object { const val SECONDS = 3 }
}

/** Shown over the whole app, so a reminder's "Call" button works from anywhere. */
@Composable
fun CallSheetHost(vm: CallViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> vm.contactsResult(r[Manifest.permission.READ_CONTACTS] == true) }
    val askCall = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.callPermissionDone() }
    val s = ui ?: return
    LaunchedEffect((s as? CallUi.Ringing)?.asking) { if ((s as? CallUi.Ringing)?.asking == true) askCall.launch(Manifest.permission.CALL_PHONE) }
    BridgeSheet(onDismiss = vm::close) {
        CallContent(s, CallSheetActions(
            allow = { askContacts.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)) },
            settings = { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            pick = vm::pick, callNow = vm::callNow, close = vm::close,
        ))
    }
}

class CallSheetActions(val allow: () -> Unit = {}, val settings: () -> Unit = {}, val pick: (PhoneContact) -> Unit = {}, val callNow: () -> Unit = {}, val close: () -> Unit = {})

@Composable
fun CallContent(s: CallUi, a: CallSheetActions) {
    val c = LocalBridgeColors.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (s) {
            is CallUi.NeedsContacts -> {
                SheetTitle(stringResource(R.string.call_title, s.name))
                Text(stringResource(R.string.call_permission_body, s.name), style = BType.body(15, lineHeight = 1.5f), color = c.ink2)
                if (s.denied) {
                    Text(stringResource(R.string.call_permission_denied), style = BType.body(13), color = c.warn)
                    PrimaryButton(stringResource(R.string.call_open_settings), a.settings, Modifier.fillMaxWidth())
                } else PrimaryButton(stringResource(R.string.call_allow), a.allow, Modifier.fillMaxWidth())
                SecondaryButton(stringResource(R.string.call_not_now), a.close, Modifier.fillMaxWidth())
            }
            is CallUi.Searching -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(color = c.acc, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.call_finding, s.name), style = BType.body(15), color = c.ink)
            }
            is CallUi.NotFound -> {
                SheetTitle(stringResource(R.string.call_not_found, s.name))
                Text(stringResource(R.string.call_not_found_body), style = BType.body(15, lineHeight = 1.5f), color = c.ink2)
                SecondaryButton(stringResource(R.string.call_close), a.close, Modifier.fillMaxWidth())
            }
            is CallUi.Pick -> {
                SheetTitle(stringResource(R.string.call_which, s.name))
                Column(Modifier.fillMaxWidth().background(c.s1, RoundedCornerShape(16.dp)).border(1.dp, c.line, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))) {
                    s.options.forEachIndexed { i, o ->
                        if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 16.dp).heightIn(min = 1.dp).background(c.line))
                        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button) { a.pick(o) }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Initial(o.name, 36)
                            Column(Modifier.weight(1f)) {
                                Text(o.name, style = BType.body(15, FontWeight.SemiBold), color = c.ink)
                                Text(listOfNotNull(o.label, o.number).joinToString(" · "), style = BType.body(13), color = c.mut)
                            }
                            Icon(painterResource(R.drawable.ic_call), null, tint = c.ink2, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                SecondaryButton(stringResource(R.string.call_cancel), a.close, Modifier.fillMaxWidth())
            }
            is CallUi.Ringing -> {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Initial(s.contact.name, 64)
                    Text(s.contact.name, style = BType.display(22), color = c.ink, modifier = Modifier.padding(top = 6.dp))
                    if (s.contact.number != s.contact.name) Text(listOfNotNull(s.contact.label, s.contact.number).joinToString(" · "), style = BType.body(14), color = c.mut)
                    Text(when { s.asking -> ""; s.seconds > 0 -> pluralStringResource(R.plurals.call_in, s.seconds, s.seconds); else -> stringResource(R.string.call_calling) }, style = BType.body(14, FontWeight.Medium), color = c.ink2,
                        modifier = Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite })
                    if (!s.direct && !s.asking) Text(stringResource(R.string.call_opens_dialer), style = BType.body(13), color = c.mut)
                }
                PrimaryButton(stringResource(R.string.call_now), a.callNow, Modifier.fillMaxWidth(), enabled = !s.asking)
                SecondaryButton(stringResource(R.string.call_cancel), a.close, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Initial(name: String, size: Int) {
    val c = LocalBridgeColors.current
    Box(Modifier.size(size.dp).background(c.accTint, CircleShape).border(1.dp, c.accLine, CircleShape), contentAlignment = Alignment.Center) {
        val ch = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#"
        Text(ch, style = BType.display(if (size > 48) 26 else 15), color = c.accInk)
    }
}
