package app.thebridge.feature.dm

import app.thebridge.ui.components.LiveTextField
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.thebridge.R
import app.thebridge.data.DmRepository
import app.thebridge.data.NetworkMonitor
import app.thebridge.data.RealtimeClient
import app.thebridge.data.SocialRepository
import app.thebridge.data.Verification
import app.thebridge.data.local.DmMessageEntity
import app.thebridge.data.local.DmPeerEntity
import app.thebridge.data.remote.DmContactDto
import app.thebridge.data.remote.OfflineException
import app.thebridge.data.verification
import app.thebridge.feature.community.Avatar
import app.thebridge.feature.community.ago
import app.thebridge.nav.DmThread
import app.thebridge.ui.Toaster
import app.thebridge.ui.components.BackButton
import app.thebridge.ui.components.BridgeSheet
import app.thebridge.ui.components.PrimaryButton
import app.thebridge.ui.components.SecondaryButton
import app.thebridge.ui.components.SheetTitle
import app.thebridge.ui.components.ToastKind
import app.thebridge.ui.components.UiText
import app.thebridge.ui.theme.BType
import app.thebridge.ui.theme.LocalBridgeColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// --- Inbox ---

data class DmRow(val contact: DmContactDto, val last: DmMessageEntity?, val unread: Boolean, val verified: Boolean)
data class InboxUi(val rows: List<DmRow> = emptyList(), val loaded: Boolean = false)

@HiltViewModel
class InboxViewModel @Inject constructor(private val dms: DmRepository, private val realtime: RealtimeClient, private val toaster: Toaster, network: NetworkMonitor) : ViewModel() {
    private val contacts = MutableStateFlow<List<DmContactDto>?>(null)
    val state: StateFlow<InboxUi> = combine(contacts, dms.threads(), dms.peers()) { cs, msgs, peers ->
        val byPeer = msgs.groupBy { it.peerUserId }
        val pm = peers.associateBy { it.userId }
        // Contacts come from the server; offline, the people we already have threads with still show.
        val list = cs ?: peers.filter { byPeer.containsKey(it.userId) }.map { DmContactDto(it.userId, it.name, true) }
        InboxUi(list.map { c ->
            val t = byPeer[c.userId].orEmpty()
            DmRow(c, t.lastOrNull(), t.any { !it.mine && !it.read }, pm[c.userId].verification() == Verification.Verified)
        }.sortedByDescending { it.last?.createdAt ?: 0 }, loaded = cs != null || msgs.isNotEmpty())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxUi())

    init {
        realtime.acquire()
        refresh()
        viewModelScope.launch { realtime.dmPings.collect { runCatching { dms.receive() } } }
        viewModelScope.launch { network.online.drop(1).collect { if (it) refresh() } }
    }

    fun refresh() = viewModelScope.launch {
        runCatching { contacts.value = dms.contacts() }
        try { dms.receive(); dms.flushQueued() } catch (e: OfflineException) { } catch (e: Exception) { toaster.show(UiText.Res(R.string.error_generic), ToastKind.Warn) }
    }

    override fun onCleared() { realtime.release() }
}

@Composable
fun InboxRoute(onBack: () -> Unit, onOpen: (String) -> Unit, vm: InboxViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    InboxScreen(ui, onBack, onOpen)
}

@Composable
fun InboxScreen(ui: InboxUi, onBack: () -> Unit, onOpen: (String) -> Unit, now: Long = System.currentTimeMillis()) {
    val c = LocalBridgeColors.current
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(Modifier.padding(start = 10.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            BackButton(onBack)
            Text(stringResource(R.string.dm_title), style = BType.display(22), color = c.ink, modifier = Modifier.semantics { heading() })
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { E2eNote(stringResource(R.string.dm_list_note), Modifier.padding(bottom = 8.dp)) }
            if (ui.loaded && ui.rows.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.dm_empty_title), style = BType.display(16), color = c.ink)
                    Text(stringResource(R.string.dm_empty_body), style = BType.body(13, lineHeight = 1.45f), color = c.mut, textAlign = TextAlign.Center)
                }
            }
            items(ui.rows, key = { it.contact.userId }) { r ->
                val preview = when {
                    r.last == null -> stringResource(R.string.dm_preview_none)
                    r.last.status == "undecryptable" -> stringResource(R.string.dm_preview_undecryptable)
                    r.last.mine -> stringResource(R.string.dm_preview_you, r.last.body)
                    else -> r.last.body
                }
                val unread = stringResource(R.string.dm_unread)
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) { onOpen(r.contact.userId) }
                    .padding(horizontal = 12.dp, vertical = 10.dp).semantics(mergeDescendants = true) { if (r.unread) contentDescription = unread },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Avatar(r.contact.name, 44.dp, c.accInk)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(r.contact.name, style = BType.body(15, FontWeight.SemiBold), color = c.ink)
                            if (r.verified) Icon(painterResource(R.drawable.ic_shield), stringResource(R.string.dm_verified), tint = c.ok, modifier = Modifier.size(15.dp))
                        }
                        Text(preview, style = BType.body(13), color = if (r.unread) c.ink2 else c.mut, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        r.last?.let { Text(ago(it.createdAt, now), style = BType.label(11f, 0f), color = c.mut2) }
                        if (r.unread) Box(Modifier.size(8.dp).background(c.acc, CircleShape))
                    }
                }
            }
            item { Text(stringResource(R.string.dm_list_footer), style = BType.body(12), color = c.mut2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) }
        }
    }
}

@Composable
private fun E2eNote(text: String, modifier: Modifier = Modifier) {
    val c = LocalBridgeColors.current
    Row(modifier.fillMaxWidth().background(c.okTint, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(R.drawable.ic_lock), null, tint = c.ok, modifier = Modifier.size(16.dp))
        Text(text, style = BType.body(12, lineHeight = 1.4f), color = c.ok)
    }
}

// --- Thread ---

data class ThreadUi(val name: String = "", val messages: List<DmMessageEntity> = emptyList(), val verification: Verification = Verification.Unverified, val input: String = "", val safety: List<List<String>>? = null)

@HiltViewModel
class ThreadViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val dms: DmRepository,
    private val social: SocialRepository,
    private val realtime: RealtimeClient,
    private val toaster: Toaster,
    network: NetworkMonitor,
) : ViewModel() {
    val peer = saved.toRoute<DmThread>().contactId
    private val input = MutableStateFlow("")
    private val safety = MutableStateFlow<List<List<String>>?>(null)
    val state: StateFlow<ThreadUi> = combine(dms.thread(peer), dms.peers(), input, safety) { msgs, peers, text, sn ->
        val p = peers.find { it.userId == peer }
        ThreadUi(p?.name.orEmpty(), msgs, p.verification(), text, sn)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThreadUi())

    init {
        realtime.acquire()
        viewModelScope.launch { runCatching { dms.receive() }; dms.markRead(peer) }
        viewModelScope.launch { realtime.dmPings.collect { runCatching { dms.receive() }; dms.markRead(peer) } }
        viewModelScope.launch { network.online.drop(1).collect { if (it) runCatching { dms.flushQueued(); dms.receive() } } }
    }
    override fun onCleared() { realtime.release() }

    fun onInput(v: String) { input.value = v.take(4000) }
    fun send() {
        val text = input.value.trim()
        if (text.isEmpty()) return
        input.value = ""
        viewModelScope.launch { dms.send(peer, text) }
    }
    fun retry(m: DmMessageEntity) = viewModelScope.launch { dms.retry(m.id) }
    fun discard(m: DmMessageEntity) = viewModelScope.launch { dms.discard(m.id) }

    fun openSafety() = viewModelScope.launch {
        try { safety.value = dms.safetyNumbers(peer) } catch (e: Exception) { toaster.show(UiText.Res(R.string.error_offline), ToastKind.Warn) }
    }
    fun closeSafety() { safety.value = null }
    fun verify() = viewModelScope.launch { dms.markVerified(peer); safety.value = null; toaster.show(UiText.Res(R.string.toast_verified), ToastKind.Ok) }

    fun report() = viewModelScope.launch {
        try { dms.report(peer, state.value.messages); toaster.show(UiText.Res(R.string.toast_dm_reported), ToastKind.Ok) }
        catch (e: Exception) { toaster.show(UiText.Res(R.string.error_offline), ToastKind.Warn) }
    }
    fun block(onDone: () -> Unit) = viewModelScope.launch {
        try { social.block(peer); toaster.show(UiText.Res(R.string.toast_blocked, listOf(state.value.name)), ToastKind.Ok); onDone() }
        catch (e: Exception) { toaster.show(UiText.Res(R.string.error_offline), ToastKind.Warn) }
    }
}

data class ThreadActions(
    val back: () -> Unit = {}, val onInput: (String) -> Unit = {}, val send: () -> Unit = {}, val retry: (DmMessageEntity) -> Unit = {}, val discard: (DmMessageEntity) -> Unit = {},
    val safety: () -> Unit = {}, val menu: () -> Unit = {},
)

@Composable
fun ThreadRoute(onBack: () -> Unit, vm: ThreadViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    var menu by rememberSaveable { mutableStateOf(false) }
    ThreadScreen(ui, ThreadActions(onBack, vm::onInput, vm::send, { vm.retry(it) }, { vm.discard(it) }, { vm.openSafety() }, { menu = true }))
    ui.safety?.let { SafetySheet(ui.name, it, onVerify = { vm.verify() }, onDismiss = vm::closeSafety) }
    if (menu) {
        val c = LocalBridgeColors.current
        BridgeSheet({ menu = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SheetTitle(stringResource(R.string.dm_report, ui.name))
                Text(stringResource(R.string.dm_report_body), style = BType.body(14, lineHeight = 1.5f), color = c.mut)
                PrimaryButton(stringResource(R.string.dm_report, ui.name), { menu = false; vm.report() })
                PrimaryButton(stringResource(R.string.dm_block, ui.name), { menu = false; vm.block(onBack) }, container = c.bad, content = c.s1)
                SecondaryButton(stringResource(R.string.ally_stay), { menu = false })
            }
        }
    }
}

@Composable
fun ThreadScreen(ui: ThreadUi, actions: ThreadActions, now: Long = System.currentTimeMillis()) {
    val c = LocalBridgeColors.current
    val list = rememberLazyListState()
    LaunchedEffect(ui.messages.size) { if (ui.messages.isNotEmpty()) list.animateScrollToItem(ui.messages.size) }
    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 8.dp, top = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BackButton(actions.back)
            Avatar(ui.name, 36.dp, c.accInk)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(ui.name, style = BType.display(17), color = c.ink, maxLines = 1, modifier = Modifier.semantics { heading() })
                Row(Modifier.heightIn(min = 24.dp).clickable(role = Role.Button, onClick = actions.safety), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(painterResource(R.drawable.ic_lock), null, tint = c.ok, modifier = Modifier.size(12.dp))
                    Text(stringResource(R.string.dm_e2e_chip), style = BType.label(9.5f, 0.12f), color = c.ok)
                }
            }
            Box(Modifier.size(44.dp).clickable(role = Role.Button, onClickLabel = stringResource(R.string.dm_block_report), onClick = actions.menu), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.dm_block_report), tint = c.mut, modifier = Modifier.size(20.dp))
            }
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 1.dp).background(c.line))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Column(Modifier.fillMaxWidth().background(c.okTint, RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(painterResource(R.drawable.ic_lock), null, tint = c.ok, modifier = Modifier.size(16.dp))
                        Text(stringResource(R.string.dm_thread_note), style = BType.body(12, lineHeight = 1.4f), color = c.ok, modifier = Modifier.weight(1f))
                    }
                    when (ui.verification) {
                        Verification.Verified -> Text(stringResource(R.string.dm_verified), style = BType.body(12, FontWeight.SemiBold), color = c.ok)
                        else -> Text(stringResource(R.string.dm_verify_link), style = BType.body(12, FontWeight.SemiBold), color = c.ok,
                            modifier = Modifier.heightIn(min = 32.dp).clickable(role = Role.Button, onClick = actions.safety).padding(vertical = 6.dp))
                    }
                }
            }
            if (ui.verification == Verification.Changed) item {
                Row(Modifier.fillMaxWidth().background(c.warnTint, RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = actions.safety).padding(12.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(painterResource(R.drawable.ic_warning), null, tint = c.warn, modifier = Modifier.size(16.dp))
                    Text(stringResource(R.string.dm_key_changed, ui.name), style = BType.body(12, lineHeight = 1.4f), color = c.warn)
                }
            }
            items(ui.messages, key = { it.id }) { m -> Bubble(m, ui.name, now, actions) }
        }
        Composer(ui.input, actions)
    }
}

@Composable
private fun Bubble(m: DmMessageEntity, name: String, now: Long, actions: ThreadActions) {
    val c = LocalBridgeColors.current
    if (m.status == "undecryptable") {
        Text(stringResource(R.string.dm_undecryptable), style = BType.body(13), color = c.mut, modifier = Modifier.fillMaxWidth().background(c.s0, RoundedCornerShape(12.dp)).padding(10.dp), textAlign = TextAlign.Center)
        return
    }
    if (!m.mine) {
        Column(Modifier.fillMaxWidth(.84f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(m.body, style = BType.body(15), color = c.ink, modifier = Modifier.background(c.s1, RoundedCornerShape(16.dp, 16.dp, 16.dp, 5.dp))
                .border(1.dp, c.line, RoundedCornerShape(16.dp, 16.dp, 16.dp, 5.dp)).padding(horizontal = 13.dp, vertical = 10.dp))
            Text(ago(m.createdAt, now), style = BType.body(11), color = c.mut2, modifier = Modifier.padding(start = 4.dp))
        }
        return
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(m.body, style = BType.body(15), color = c.ink, modifier = Modifier.widthIn(max = 300.dp).background(c.bubble, RoundedCornerShape(16.dp, 16.dp, 5.dp, 16.dp)).padding(horizontal = 13.dp, vertical = 10.dp))
        when (m.status) {
            "queued" -> Text(stringResource(R.string.dm_status_queued), style = BType.body(11, FontWeight.Medium), color = c.warn)
            "no_devices" -> Text(stringResource(R.string.dm_status_no_devices, name), style = BType.body(11, FontWeight.Medium), color = c.warn, textAlign = TextAlign.End)
            "failed" -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.chat_discard), style = BType.body(12, FontWeight.Medium), color = c.mut, modifier = Modifier.heightIn(min = 44.dp).clickable(role = Role.Button) { actions.discard(m) }.padding(vertical = 13.dp))
                Text(stringResource(R.string.dm_status_failed), style = BType.body(12, FontWeight.Medium), color = c.bad, modifier = Modifier.heightIn(min = 44.dp).clickable(role = Role.Button) { actions.retry(m) }.padding(vertical = 13.dp))
            }
            else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(painterResource(R.drawable.ic_check), null, tint = c.mut, modifier = Modifier.size(13.dp))
                Text(stringResource(R.string.dm_status_sent), style = BType.body(11), color = c.mut)
            }
        }
    }
}

@Composable
private fun Composer(text: String, actions: ThreadActions) {
    val c = LocalBridgeColors.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val placeholder = stringResource(R.string.dm_placeholder)
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        LiveTextField(text, actions.onInput, Modifier.weight(1f).semantics { contentDescription = placeholder }, textStyle = BType.body(15).copy(color = c.ink), cursorBrush = SolidColor(c.ok),
            interactionSource = interaction, maxLines = 4, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { actions.send() }),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(c.s1, RoundedCornerShape(24.dp)).border(1.dp, if (focused) c.ok else c.line, RoundedCornerShape(24.dp)).padding(horizontal = 18.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(painterResource(R.drawable.ic_lock), null, tint = c.mut2, modifier = Modifier.size(14.dp))
                        Text(placeholder, style = BType.body(15), color = c.mut2)
                    }
                    inner()
                }
            })
        val canSend = text.isNotBlank()
        Box(Modifier.size(48.dp).clip(CircleShape).background(c.acc.copy(alpha = if (canSend) 1f else .35f)).clickable(enabled = canSend, role = Role.Button, onClickLabel = stringResource(R.string.chat_send), onClick = actions.send),
            contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_arrow_upward), stringResource(R.string.chat_send), tint = c.onAcc, modifier = Modifier.size(22.dp))
        }
    }
}

/** 12 groups of 5 digits per device of theirs, compared in person or on a call. */
@Composable
fun SafetySheet(name: String, numbers: List<List<String>>, onVerify: () -> Unit, onDismiss: () -> Unit) {
    BridgeSheet(onDismiss) { SafetyContent(name, numbers, onVerify, onDismiss) }
}

@Composable
fun SafetyContent(name: String, numbers: List<List<String>>, onVerify: () -> Unit, onDismiss: () -> Unit) {
    val c = LocalBridgeColors.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SheetTitle(stringResource(R.string.safety_title))
        Text(stringResource(R.string.safety_body, name), style = BType.body(14, lineHeight = 1.5f), color = c.mut)
        numbers.forEachIndexed { i, groups ->
            if (numbers.size > 1) Text(stringResource(R.string.safety_device, i + 1), style = BType.label(10f, 0.12f), color = c.mut)
            val a11y = stringResource(R.string.safety_a11y, groups.joinToString(" "))
            Column(Modifier.fillMaxWidth().background(c.s1, RoundedCornerShape(16.dp)).border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(16.dp).semantics { contentDescription = a11y },
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                groups.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        row.forEach { Text(it, style = BType.label(17f, 0.04f), color = c.ink) }
                    }
                }
            }
        }
        PrimaryButton(stringResource(R.string.safety_mark), onVerify)
        SecondaryButton(stringResource(R.string.safety_not_now), onDismiss)
    }
}
