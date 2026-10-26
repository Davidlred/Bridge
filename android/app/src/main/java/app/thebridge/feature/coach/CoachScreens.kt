package app.thebridge.feature.coach

import androidx.compose.ui.draw.clip
import app.thebridge.ui.components.LiveTextField
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxWidth as fw
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.thebridge.R
import app.thebridge.ui.components.PrimaryButton
import app.thebridge.ui.theme.BType
import app.thebridge.ui.theme.LocalBridgeColors
import app.thebridge.ui.theme.LocalReduceMotion
import kotlinx.coroutines.delay

data class CoachActions(
    val onInput: (String) -> Unit = {}, val send: () -> Unit = {}, val chip: (String) -> Unit = {}, val retry: (ChatItem) -> Unit = {},
    val add: (ChatItem) -> Unit = {}, val dismiss: (ChatItem) -> Unit = {}, val showEarlier: () -> Unit = {}, val newChat: () -> Unit = {}, val study: () -> Unit = {},
    val focus: (String) -> Unit = {}, val openAlliance: (ChatItem) -> Unit = {},
)

/** Mascot status line and dot colour for each mood (copy from the prototype). */
@Composable
private fun statusFor(mood: MascotMood): Pair<String, Color> {
    val c = LocalBridgeColors.current
    return when (mood) {
        MascotMood.Idle -> stringResource(R.string.coach_status_idle) to c.mut2
        MascotMood.Listening -> stringResource(R.string.coach_status_listening) to c.acc
        MascotMood.Thinking -> stringResource(R.string.coach_status_thinking) to c.warn
        MascotMood.Talking -> stringResource(R.string.coach_status_talking) to c.ok
        MascotMood.Happy -> stringResource(R.string.coach_status_happy) to c.ok
    }
}

/** Re-reads the clock while something time-based (the happy face) is showing. */
@Composable
private fun rememberNow(until: Long): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(until) { while (System.currentTimeMillis() < until) { delay(100); now = System.currentTimeMillis() }; now = System.currentTimeMillis() }
    return now
}

@Composable
fun CoachScreen(ui: CoachUi, actions: CoachActions) {
    val c = LocalBridgeColors.current
    var focused by remember { mutableStateOf(false) }
    val now = rememberNow(ui.happyUntil)
    val mood = ui.mood(now, focused && ui.input.isNotEmpty())
    val (status, dot) = statusFor(mood)
    val list = rememberLazyListState()
    val last = ui.items.lastOrNull()
    LaunchedEffect(ui.items.size, last?.message?.text?.length, ui.awaiting) { if (ui.items.isNotEmpty()) list.animateScrollToItem(list.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1) }

    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val label = stringResource(R.string.coach_mascot, ui.coachName)
                Box(Modifier.padding(top = 6.dp).semantics { contentDescription = label }) { CoachMascot(mood, reduceMotion = LocalReduceMotion.current) }
                Text(if (ui.loaded) ui.coachName else "", style = BType.display(21), color = c.ink, modifier = Modifier.semantics { heading() })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                    Box(Modifier.size(6.dp).background(dot, CircleShape)); Text(status, style = BType.body(12, FontWeight.Medium), color = c.mut)
                }
            }
            Row(Modifier.align(Alignment.TopEnd).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.heightIn(min = 44.dp).background(c.s1, RoundedCornerShape(22.dp)).border(1.dp, c.line, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).clickable(role = Role.Button, onClick = actions.study).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(painterResource(R.drawable.ic_focus), null, tint = c.acc, modifier = Modifier.size(19.dp))
                    Text(stringResource(R.string.coach_study), style = BType.body(13, FontWeight.Medium), color = c.ink)
                }
                Box(Modifier.size(44.dp).background(c.s1, CircleShape).border(1.dp, c.line, CircleShape).clip(CircleShape).clickable(role = Role.Button, onClick = actions.newChat), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_add_comment), stringResource(R.string.coach_new_chat), tint = c.ink, modifier = Modifier.size(20.dp))
                }
            }
        }
        // One conversation per goal: switch between them here.
        if (ui.goals.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ui.goals.forEach { g ->
                val on = g.goal.id == ui.focus?.goal?.id
                Text(g.goal.label, style = BType.body(13, FontWeight.SemiBold), color = if (on) c.ink else c.mut, maxLines = 1,
                    modifier = Modifier.heightIn(min = 40.dp).background(if (on) c.accTint else Color.Transparent, RoundedCornerShape(20.dp)).border(1.dp, if (on) c.line2 else c.line, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp))
                        .clickable(role = Role.Tab) { actions.focus(g.goal.id) }.semantics { selected = on }.padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }
        HorizontalDivider(color = c.line)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (ui.hasEarlier) item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.coach_show_earlier), style = BType.body(12, FontWeight.Medium), color = c.mut,
                        modifier = Modifier.heightIn(min = 36.dp).border(1.dp, c.line, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).clickable(role = Role.Button, onClick = actions.showEarlier).padding(horizontal = 14.dp, vertical = 9.dp))
                }
            }
            if (ui.loaded && ui.items.isEmpty()) item { Greeting(ui) }
            items(ui.items, key = { it.message.id }) { item ->
                if (item.dividerBefore) Divider()
                if (item.message.role == "coach") CoachBubble(item, ui, actions) else UserBubble(item, actions)
            }
            if (ui.awaiting) item { TypingDots() }
        }
        ui.notice?.let { Text(it.resolve(), style = BType.body(13), color = c.warn, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Polite }) }
        if (!ui.busy && ui.input.isEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(R.string.coach_chip_meditate, R.string.coach_chip_missed, R.string.coach_chip_easier).forEach { id ->
                val text = stringResource(id)
                Text(text, style = BType.body(13, FontWeight.Medium), color = c.accInk, maxLines = 1,
                    modifier = Modifier.heightIn(min = 36.dp).border(1.dp, c.accLine, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).clickable(role = Role.Button) { actions.chip(text) }.padding(horizontal = 14.dp, vertical = 9.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val hint = stringResource(R.string.coach_input_hint)
            LiveTextField(ui.input, actions.onInput, singleLine = true, textStyle = BType.body(15).copy(color = c.ink), cursorBrush = SolidColor(c.acc),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { actions.send() }),
                modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused }.semantics { contentDescription = hint },
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(c.s1, RoundedCornerShape(24.dp)).border(1.dp, if (focused) c.acc else c.line, RoundedCornerShape(24.dp)).padding(horizontal = 18.dp), contentAlignment = Alignment.CenterStart) {
                        if (ui.input.isEmpty()) Text(hint, style = BType.body(15), color = c.mut2); inner()
                    }
                })
            SendButton(enabled = ui.input.isNotBlank() && !ui.busy, size = 48, onClick = actions.send)
        }
    }
}

@Composable
private fun SendButton(enabled: Boolean, size: Int, onClick: () -> Unit) {
    val c = LocalBridgeColors.current
    Box(Modifier.size(size.dp).alpha(if (enabled) 1f else .35f).background(c.acc, CircleShape).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_arrow_upward), stringResource(R.string.coach_send), tint = c.onAcc, modifier = Modifier.size(if (size > 44) 22.dp else 20.dp))
    }
}

@Composable
private fun Greeting(ui: CoachUi) {
    val c = LocalBridgeColors.current
    Text(stringResource(if (ui.goals.isEmpty()) R.string.coach_empty_body else R.string.coach_greeting), style = BType.body(15, lineHeight = 1.5f), color = c.ink,
        modifier = Modifier.widthIn(max = 320.dp).background(c.s1, RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)).border(1.dp, c.line, RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)).padding(horizontal = 14.dp, vertical = 12.dp))
}

@Composable
private fun Divider() {
    val c = LocalBridgeColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f).heightIn(min = 1.dp).background(c.track)); Text(stringResource(R.string.coach_new_conversation), style = BType.label(10f, 0.14f), color = c.mut2); Box(Modifier.weight(1f).heightIn(min = 1.dp).background(c.track))
    }
}

@Composable
private fun CoachBubble(item: ChatItem, ui: CoachUi, actions: CoachActions) {
    val c = LocalBridgeColors.current
    val m = item.message
    Column(Modifier.fw(.86f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (m.text.isNotBlank()) Text(m.text.trimEnd(), style = BType.body(15, lineHeight = 1.5f), color = c.ink,
            modifier = Modifier.background(c.s1, RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)).border(1.dp, c.line, RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)).padding(horizontal = 14.dp, vertical = 12.dp))
        if (m.status == "interrupted") Text(stringResource(R.string.coach_interrupted), style = BType.body(12, FontWeight.Medium), color = c.bad,
            modifier = Modifier.heightIn(min = 32.dp).clickable(role = Role.Button) { actions.retry(item) }.padding(vertical = 6.dp))
        val s = item.suggestion
        // The card appears only after the stream finishes.
        if (s != null && m.status == "done") when (m.suggestionState) {
            "added" -> Row(Modifier.fillMaxWidth().background(c.okTint, RoundedCornerShape(12.dp)).border(1.dp, c.ok, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.ic_check_circle), null, tint = c.ok, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.coach_added, s.title), style = BType.body(13, FontWeight.Medium), color = c.ok)
            }
            "dismissed" -> Text(stringResource(R.string.coach_dismissed), style = BType.body(12), color = c.mut2, modifier = Modifier.padding(start = 4.dp))
            else -> Column(Modifier.fillMaxWidth().background(c.accTint, RoundedCornerShape(16.dp)).border(1.dp, c.accLine, RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.coach_add_to_plan), style = BType.label(10f, 0.14f), color = c.mut)
                Text(s.title, style = BType.display(15, 1.3f), color = c.ink)
                if (s.rationale.isNotBlank()) Text(s.rationale, style = BType.body(13, lineHeight = 1.45f), color = c.mut)
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f).heightIn(min = 44.dp).background(c.acc, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).clickable(enabled = ui.focus != null, role = Role.Button) { actions.add(item) }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.coach_add_to, ui.focus?.goal?.label.orEmpty()), style = BType.body(14, FontWeight.SemiBold), color = c.onAcc, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(Modifier.heightIn(min = 44.dp).border(1.dp, c.line, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { actions.dismiss(item) }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.coach_dismiss), style = BType.body(14, FontWeight.Medium), color = c.ink2)
                    }
                }
            }
        }
        // An alliance the coach recommended: see it (its join preview opens in Community), or dismiss it.
        val a = item.alliance
        if (a != null && m.status == "done") {
            if (m.suggestionState == "dismissed") Text(stringResource(R.string.coach_dismissed), style = BType.body(12), color = c.mut2, modifier = Modifier.padding(start = 4.dp))
            else Column(Modifier.fillMaxWidth().background(c.accTint, RoundedCornerShape(16.dp)).border(1.dp, c.accLine, RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(painterResource(R.drawable.ic_groups), null, tint = c.ink2, modifier = Modifier.size(18.dp))
                    Text(a.name, style = BType.display(15, 1.3f), color = c.ink, modifier = Modifier.weight(1f))
                }
                Text(stringResource(if (a.planMode == "shared") R.string.coach_alliance_meta_shared else R.string.coach_alliance_meta_own, a.members, a.cap), style = BType.body(12), color = c.mut)
                a.about?.takeIf { it.isNotBlank() }?.let { Text(it, style = BType.body(13, lineHeight = 1.45f), color = c.ink2) }
                if (a.reason.isNotBlank()) Text(a.reason, style = BType.body(13, lineHeight = 1.45f), color = c.mut)
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f).heightIn(min = 44.dp).background(c.acc, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { actions.openAlliance(item) }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.coach_alliance_open), style = BType.body(14, FontWeight.SemiBold), color = c.onAcc, maxLines = 1)
                    }
                    Box(Modifier.heightIn(min = 44.dp).border(1.dp, c.line, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { actions.dismiss(item) }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.coach_dismiss), style = BType.body(14, FontWeight.Medium), color = c.ink2)
                    }
                }
            }
        }
    }
}

@Composable
private fun UserBubble(item: ChatItem, actions: CoachActions) {
    val c = LocalBridgeColors.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Hugs short text; long text wraps at 80% of the width.
        Box(Modifier.fw(.8f), contentAlignment = Alignment.CenterEnd) {
            Text(item.message.text, style = BType.body(15, lineHeight = 1.5f), color = c.ink,
                modifier = Modifier.background(c.bubble, RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp)).padding(horizontal = 14.dp, vertical = 12.dp))
        }
        if (item.message.status == "failed") Row(Modifier.heightIn(min = 32.dp).clickable(role = Role.Button) { actions.retry(item) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(painterResource(R.drawable.ic_error), null, tint = c.bad, modifier = Modifier.size(16.dp))
            Text(stringResource(R.string.coach_not_sent), style = BType.body(12, FontWeight.Medium), color = c.bad)
        }
    }
}

@Composable
private fun TypingDots() {
    val c = LocalBridgeColors.current
    val reduce = LocalReduceMotion.current
    val t = rememberInfiniteTransition(label = "dots")
    Row(Modifier.background(c.s1, RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { i ->
            val y = if (reduce) 0f else t.animateFloat(0f, 1f, infiniteRepeatable(tween(600, delayMillis = i * 150), RepeatMode.Reverse), label = "d$i").value
            Box(Modifier.size(7.dp).offset(y = (-4 * y).dp).alpha(.4f + .6f * y).background(c.mut, CircleShape))
        }
    }
}

/** Full-screen focus timer with notes and a mini coach strip at the bottom. Pauses 10 s after the app is backgrounded. */
@Composable
fun StudyModeScreen(ui: CoachUi, onNotes: (String) -> Unit, onInput: (String) -> Unit, send: () -> Unit, add: (ChatItem) -> Unit, closeReply: () -> Unit,
                    onBackground: () -> Unit, onForeground: () -> Unit, resume: () -> Unit, exit: () -> Unit) {
    val c = LocalBridgeColors.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) onBackground() else if (e == Lifecycle.Event.ON_START) onForeground() }
        lifecycle.addObserver(o); onDispose { lifecycle.removeObserver(o) }
    }
    BackHandler(onBack = exit)
    val now = rememberNow(ui.happyUntil)
    val mood = ui.mood(now, ui.studyInput.isNotEmpty())
    Box(Modifier.fillMaxSize().background(c.s0).imePadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                val pulse = if (LocalReduceMotion.current) 1f else rememberInfiniteTransition(label = "p").animateFloat(.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a").value
                Box(Modifier.size(7.dp).alpha(pulse).background(c.acc, CircleShape))
                Text(stringResource(R.string.study_mode), style = BType.label(11f, 0.14f), color = c.mut, modifier = Modifier.padding(start = 10.dp))
                Text("%02d:%02d".format(ui.studySeconds / 60, ui.studySeconds % 60), style = BType.label(14f, 0f), color = c.ink, modifier = Modifier.padding(start = 10.dp))
                Spacer(Modifier.weight(1f))
                Row(Modifier.heightIn(min = 44.dp).border(1.dp, c.line, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).clickable(role = Role.Button, onClick = exit).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(painterResource(R.drawable.ic_close_fullscreen), null, tint = c.ink, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.study_exit), style = BType.body(13, FontWeight.Medium), color = c.ink)
                }
            }
            ui.focus?.let { Text(stringResource(R.string.study_working_toward, it.goal.statement), style = BType.body(12), color = c.mut2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 22.dp)) }
            val hint = stringResource(R.string.study_notes_hint)
            LiveTextField(ui.studyNotes, onNotes, textStyle = BType.body(18, lineHeight = 1.65f).copy(color = c.ink), cursorBrush = SolidColor(c.acc),
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 150.dp).semantics { contentDescription = hint },
                decorationBox = { inner -> Box { if (ui.studyNotes.isEmpty()) Text(hint, style = BType.body(18, lineHeight = 1.65f), color = c.mut2); inner() } })
        }
        // The coach's reply floats above the strip.
        val reply = ui.items.firstOrNull { it.message.id == ui.studyReply }
        if (reply != null || (ui.busy && ui.studyReply == null && ui.items.lastOrNull()?.message?.status == "streaming")) {
            val shown = reply ?: ui.items.last()
            Column(Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 100.dp).fillMaxWidth().background(c.s1, RoundedCornerShape(18.dp)).border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.study_coach), style = BType.label(10f, 0.14f), color = c.acc)
                    Box(Modifier.size(32.dp).clickable(role = Role.Button, onClick = closeReply), contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.cancel), tint = c.mut, modifier = Modifier.size(18.dp)) }
                }
                Text(shown.message.text, style = BType.body(14, lineHeight = 1.5f), color = c.ink, modifier = Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState()).semantics { liveRegion = LiveRegionMode.Polite })
                val s = shown.suggestion
                if (s != null && shown.message.status == "done") when (shown.message.suggestionState) {
                    "added" -> Text(stringResource(R.string.coach_added_today), style = BType.body(12, FontWeight.Medium), color = c.ok)
                    "pending" -> Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).background(c.accTint, RoundedCornerShape(12.dp)).border(1.dp, c.accLine, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { add(shown) },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                        Icon(painterResource(R.drawable.ic_add), null, tint = c.accInk, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.study_add, s.title), style = BType.body(13, FontWeight.SemiBold), color = c.accInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 30.dp).fillMaxWidth().heightIn(min = 58.dp).background(c.s1, RoundedCornerShape(29.dp)).border(1.dp, c.line, RoundedCornerShape(29.dp)).padding(start = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CoachMascot(mood, size = 34.dp, reduceMotion = LocalReduceMotion.current)
            val hint = stringResource(R.string.study_ask_hint)
            LiveTextField(ui.studyInput, onInput, singleLine = true, textStyle = BType.body(15).copy(color = c.ink), cursorBrush = SolidColor(c.acc),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.weight(1f).semantics { contentDescription = hint },
                decorationBox = { inner -> Box(Modifier.heightIn(min = 44.dp), contentAlignment = Alignment.CenterStart) { if (ui.studyInput.isEmpty()) Text(hint, style = BType.body(15), color = c.mut2); inner() } })
            SendButton(enabled = ui.studyInput.isNotBlank() && !ui.busy, size = 44, onClick = send)
        }
        if (ui.studyPaused) Column(Modifier.align(Alignment.Center).padding(24.dp).background(c.s1, RoundedCornerShape(20.dp)).border(1.dp, c.line, RoundedCornerShape(20.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.study_paused_title), style = BType.display(20), color = c.ink, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.study_paused_body), style = BType.body(14), color = c.mut)
            PrimaryButton(stringResource(R.string.study_resume), resume)
        }
    }
}
