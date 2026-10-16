package app.thebridge.feature.community

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.thebridge.R
import app.thebridge.data.NetworkMonitor
import app.thebridge.data.RealtimeClient
import app.thebridge.data.SocialRepository
import app.thebridge.data.local.AllianceMessageEntity
import app.thebridge.data.remote.AllianceDto
import app.thebridge.nav.AllianceChat
import app.thebridge.ui.Toaster
import app.thebridge.ui.components.BackButton
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

data class AllianceChatUi(val alliance: AllianceDto? = null, val messages: List<AllianceMessageEntity> = emptyList(), val input: String = "")

@HiltViewModel
class AllianceChatViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val social: SocialRepository,
    private val realtime: RealtimeClient,
    private val toaster: Toaster,
    network: NetworkMonitor,
) : ViewModel() {
    private val allianceId = saved.toRoute<AllianceChat>().allianceId
    private val input = MutableStateFlow("")

    val state: StateFlow<AllianceChatUi> = combine(social.alliances(), social.messages(allianceId), input) { all, msgs, text ->
        AllianceChatUi(all.find { it.id == allianceId }, msgs, text)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AllianceChatUi())

    init {
        realtime.acquire()                                   // live while this screen exists
        viewModelScope.launch { runCatching { social.refreshMessages(allianceId) } }
        viewModelScope.launch { network.online.drop(1).collect { if (it) { social.flushQueued(); runCatching { social.refreshMessages(allianceId) } } } }
    }

    override fun onCleared() { realtime.release() }

    fun onInput(v: String) { input.value = v.take(2000) }

    fun send() {
        val text = input.value.trim()
        if (text.isEmpty()) return
        input.value = ""
        viewModelScope.launch { report(social.send(allianceId, text)) }
    }

    fun retry(m: AllianceMessageEntity) = viewModelScope.launch { report(social.retry(m)) }
    fun discard(m: AllianceMessageEntity) = viewModelScope.launch { social.discard(m) }

    fun reportMessage(m: AllianceMessageEntity) = viewModelScope.launch {
        try { social.reportMessage(m, "other"); toaster.show(UiText.Res(R.string.toast_reported), ToastKind.Ok) }
        catch (e: Exception) { toaster.show(UiText.Res(R.string.error_offline), ToastKind.Warn) }
    }

    private fun report(r: SocialRepository.SendResult) {
        if (r == SocialRepository.SendResult.Blocked) toaster.show(UiText.Res(R.string.chat_blocked), ToastKind.Warn)
    }
}

data class ChatActions(
    val back: () -> Unit = {}, val onInput: (String) -> Unit = {}, val send: () -> Unit = {}, val retry: (AllianceMessageEntity) -> Unit = {},
    val discard: (AllianceMessageEntity) -> Unit = {}, val report: (AllianceMessageEntity) -> Unit = {},
)

/** Alliance chat: not end-to-end encrypted (that note stays at the top), unlike direct messages. */
@Composable
fun AllianceChatScreen(ui: AllianceChatUi, actions: ChatActions, now: Long = System.currentTimeMillis()) {
    val c = LocalBridgeColors.current
    val list = rememberLazyListState()
    LaunchedEffect(ui.messages.size) { if (ui.messages.isNotEmpty()) list.animateScrollToItem(ui.messages.size) }
    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 16.dp, top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BackButton(actions.back)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(ui.alliance?.name.orEmpty(), style = BType.display(18), color = c.ink, maxLines = 1, modifier = Modifier.semantics { heading() })
                ui.alliance?.let { Text(stringResource(R.string.chat_header_meta, it.memberCount, it.streak), style = BType.label(10f, 0.12f), color = c.mut) }
            }
            Icon(painterResource(R.drawable.ic_fire), null, tint = c.acc, modifier = Modifier.size(20.dp))
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 1.dp).background(c.line))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.fillMaxWidth().background(c.s0, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(painterResource(R.drawable.ic_lock), null, tint = c.mut, modifier = Modifier.size(15.dp))
                    Text(stringResource(R.string.chat_not_e2e), style = BType.body(12, lineHeight = 1.4f), color = c.mut)
                }
            }
            if (ui.messages.isEmpty()) item { Text(stringResource(R.string.chat_empty), style = BType.body(13), color = c.mut, modifier = Modifier.padding(top = 8.dp)) }
            items(ui.messages, key = { it.id }) { m -> if (m.mine) Mine(m, actions) else if (m.userId == null) SystemLine(m) else Theirs(m, now, actions) }
        }
        Composer(ui.input, actions)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Theirs(m: AllianceMessageEntity, now: Long, actions: ChatActions) {
    val c = LocalBridgeColors.current
    val options = stringResource(R.string.report_message)
    Row(Modifier.widthIn(max = 340.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        Avatar(m.name, 30.dp, c.accInk)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${m.name.orEmpty()} · ${ago(m.createdAt, now)}", style = BType.body(11, FontWeight.Medium), color = c.mut, modifier = Modifier.padding(start = 4.dp))
            // Long press to report
            Text(m.body, style = BType.body(15), color = c.ink, modifier = Modifier.clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 5.dp)).background(c.s1)
                .border(1.dp, c.line, RoundedCornerShape(16.dp, 16.dp, 16.dp, 5.dp)).combinedClickable(onClickLabel = null, onLongClickLabel = options, onLongClick = { actions.report(m) }, onClick = {})
                .padding(horizontal = 13.dp, vertical = 10.dp))
        }
    }
}

@Composable
private fun SystemLine(m: AllianceMessageEntity) {
    val c = LocalBridgeColors.current
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(Modifier.heightIn(min = 28.dp).background(c.okTint, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(R.drawable.ic_check_circle), null, tint = c.ok, modifier = Modifier.size(15.dp))
            Text(m.body, style = BType.body(12, FontWeight.Medium), color = c.ok)
        }
    }
}

@Composable
private fun Mine(m: AllianceMessageEntity, actions: ChatActions) {
    val c = LocalBridgeColors.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(m.body, style = BType.body(15), color = c.ink, modifier = Modifier.widthIn(max = 300.dp).background(c.bubble, RoundedCornerShape(16.dp, 16.dp, 5.dp, 16.dp)).padding(horizontal = 13.dp, vertical = 10.dp))
        when (m.status) {
            "queued" -> Text(stringResource(R.string.chat_queued), style = BType.body(11, FontWeight.Medium), color = c.warn)
            "failed" -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.chat_discard), style = BType.body(12, FontWeight.Medium), color = c.mut,
                    modifier = Modifier.heightIn(min = 44.dp).clickable(role = Role.Button) { actions.discard(m) }.padding(vertical = 13.dp))
                Text(stringResource(R.string.chat_failed), style = BType.body(12, FontWeight.Medium), color = c.bad,
                    modifier = Modifier.heightIn(min = 44.dp).clickable(role = Role.Button) { actions.retry(m) }.padding(vertical = 13.dp))
            }
        }
    }
}

@Composable
private fun Composer(text: String, actions: ChatActions) {
    val c = LocalBridgeColors.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val placeholder = stringResource(R.string.chat_placeholder)
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(text, actions.onInput, Modifier.weight(1f).semantics { contentDescription = placeholder }, textStyle = BType.body(15).copy(color = c.ink), cursorBrush = SolidColor(c.acc),
            interactionSource = interaction, maxLines = 4,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { actions.send() }),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(c.s1, RoundedCornerShape(24.dp)).border(1.dp, if (focused) c.acc else c.line, RoundedCornerShape(24.dp)).padding(horizontal = 18.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) Text(placeholder, style = BType.body(15), color = c.mut2)
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
