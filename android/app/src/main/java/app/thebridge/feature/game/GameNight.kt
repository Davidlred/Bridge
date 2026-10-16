package app.thebridge.feature.game

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.thebridge.R
import app.thebridge.data.RealtimeClient
import app.thebridge.data.SocialRepository
import app.thebridge.data.remote.GameDto
import app.thebridge.data.session.SessionManager
import app.thebridge.feature.community.Avatar
import app.thebridge.feature.community.dashed
import app.thebridge.nav.GameNight
import app.thebridge.ui.Toaster
import app.thebridge.ui.components.PrimaryButton
import app.thebridge.ui.components.SecondaryButton
import app.thebridge.ui.components.ToastKind
import app.thebridge.ui.components.UiText
import app.thebridge.ui.theme.BType
import app.thebridge.ui.theme.LocalBridgeColors
import app.thebridge.ui.theme.Motion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

data class GameUi(
    val me: String? = null,
    val game: GameDto? = null,
    /** Told only to this player by the server. */
    val role: String? = null,
    val revealed: Boolean = false,
    val ready: Boolean = false,
    val begun: Boolean = false,
    val choice: String? = null,
    val voted: Boolean = false,
    val notice: UiText? = null,
    val connected: Boolean = true,
    val now: Long = System.currentTimeMillis(),
    /** Moves made on this device during the round (the server only reports totals at the end). */
    val mine: Int = 0,
) {
    val isBuilder: Boolean get() = role != "drifter"
    val secondsLeft: Int get() = (((game?.endsAt ?: 0) - now) / 1000).toInt().coerceAtLeast(0)
    val myActions: Int get() = game?.players?.find { it.userId == me && game.phase == "result" }?.actions ?: mine
}

/** Game Night: the server runs the game; this screen sends intents and shows what the server says. */
@HiltViewModel
class GameViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val social: SocialRepository,
    private val realtime: RealtimeClient,
    session: SessionManager,
    private val toaster: Toaster,
) : ViewModel() {
    private val allianceId = saved.toRoute<GameNight>().allianceId
    private val _state = MutableStateFlow(GameUi(me = session.user.value?.id))
    val state = _state.asStateFlow()
    private var moves = 0

    init {
        realtime.acquire()
        viewModelScope.launch { realtime.events.collect(::handle) }
        viewModelScope.launch {
            realtime.connected.collect { up ->
                _state.update { it.copy(connected = up) }
                if (up) join()                                    // first connect and every reconnect
            }
        }
        viewModelScope.launch { while (true) { delay(500); _state.update { it.copy(now = System.currentTimeMillis()) } } }
        viewModelScope.launch { open() }
    }

    private suspend fun open() {
        try { val g = social.openGame(allianceId); _state.update { GameUi(me = it.me, game = g, connected = it.connected) }; join() }
        catch (e: Exception) { toaster.show(UiText.Res(R.string.error_offline), ToastKind.Warn) }
    }

    private fun frame(type: String, extra: JsonObject.() -> JsonObject = { this }) {
        val id = _state.value.game?.id ?: return
        realtime.send(buildJsonObject { put("type", type); put("gameId", id) }.extra())
    }
    private fun join() = frame("game:join")

    private fun handle(e: JsonObject) {
        when (e["type"]?.jsonPrimitive?.content) {
            "game:state" -> {
                val g = runCatching { social.json.decodeFromJsonElement(GameDto.serializer(), e["game"]!!) }.getOrNull() ?: return
                if (g.id != _state.value.game?.id) return
                _state.update { it.copy(game = g) }
            }
            "game:role" -> if (e["gameId"]?.jsonPrimitive?.content == _state.value.game?.id) _state.update { it.copy(role = e["role"]?.jsonPrimitive?.content) }
            "game:error" -> when (e["error"]?.jsonPrimitive?.content) {
                "game_over" -> _state.update { it.copy(notice = UiText.Res(R.string.game_over)) }
                "already_started" -> _state.update { it.copy(notice = UiText.Res(R.string.game_already_started)) }
            }
        }
    }

    fun ready() { _state.update { it.copy(ready = true) }; frame("game:ready") }
    fun reveal() = _state.update { it.copy(revealed = true) }
    fun begin() { _state.update { it.copy(begun = true) }; frame("game:begin") }
    fun act(plank: Int) {
        val s = _state.value
        val p = s.game?.planks?.getOrNull(plank) ?: return
        if ((s.isBuilder && p == "cracked") || (!s.isBuilder && p == "ok")) { moves++; _state.update { it.copy(mine = moves) } }
        frame("game:act") { JsonObject(this + ("plank" to kotlinx.serialization.json.JsonPrimitive(plank))) }
    }
    fun choose(userId: String) { if (!_state.value.voted) _state.update { it.copy(choice = userId) } }
    fun lockVote() {
        val target = _state.value.choice ?: return
        _state.update { it.copy(voted = true) }
        frame("game:vote") { JsonObject(this + ("target" to kotlinx.serialization.json.JsonPrimitive(target))) }
    }
    fun share() = viewModelScope.launch {
        val id = _state.value.game?.id ?: return@launch
        try { social.shareGame(allianceId, id); toaster.show(UiText.Res(R.string.toast_game_shared), ToastKind.Ok) }
        catch (e: Exception) { toaster.show(UiText.Res(R.string.error_offline), ToastKind.Warn) }
    }
    fun rematch() = viewModelScope.launch { moves = 0; open() }
    fun leave() = frame("game:leave")

    override fun onCleared() { leave(); realtime.release() }
}

@Composable
fun GameRoute(onExit: () -> Unit, vm: GameViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    GameScreen(ui, GameActions(exit = { vm.leave(); onExit() }, ready = vm::ready, reveal = vm::reveal, begin = vm::begin, act = vm::act, choose = vm::choose,
        lock = vm::lockVote, share = { vm.share() }, rematch = { vm.rematch() }))
}

data class GameActions(
    val exit: () -> Unit = {}, val ready: () -> Unit = {}, val reveal: () -> Unit = {}, val begin: () -> Unit = {}, val act: (Int) -> Unit = {},
    val choose: (String) -> Unit = {}, val lock: () -> Unit = {}, val share: () -> Unit = {}, val rematch: () -> Unit = {},
)

@Composable
fun GameScreen(ui: GameUi, actions: GameActions) {
    val c = LocalBridgeColors.current
    Column(Modifier.fillMaxSize().background(c.s1)) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clickable(role = Role.Button, onClickLabel = stringResource(R.string.game_leave), onClick = actions.exit), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.game_leave), tint = c.ink, modifier = Modifier.size(24.dp))
            }
            Text(stringResource(R.string.game_top), style = BType.label(10f, 0.16f), color = c.acc, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(44.dp))
        }
        if (!ui.connected) Text(stringResource(R.string.game_reconnecting), style = BType.body(12, FontWeight.Medium), color = c.warn,
            modifier = Modifier.fillMaxWidth().background(c.warnTint).padding(8.dp).semantics { liveRegion = LiveRegionMode.Polite }, textAlign = TextAlign.Center)
        val g = ui.game
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 36.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            ui.notice?.let { Text(it.resolve(), style = BType.body(14), color = c.mut) }
            if (g != null) AnimatedContent(g.phase, label = "phase", transitionSpec = { fadeIn(tween(Motion.fade)) togetherWith fadeOut(tween(Motion.fade)) }) { phase ->
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    when (phase) {
                        "lobby" -> Lobby(ui, g, actions)
                        "role" -> RoleCard(ui, actions)
                        "round" -> Round(ui, g, actions)
                        "vote" -> Council(ui, g, actions)
                        "result" -> Result(ui, g, actions)
                        else -> {
                            Text(stringResource(R.string.game_cancelled), style = BType.body(15, lineHeight = 1.5f), color = c.mut)
                            SecondaryButton(stringResource(R.string.game_done), actions.exit)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Lobby(ui: GameUi, g: GameDto, actions: GameActions) {
    val c = LocalBridgeColors.current
    Text(stringResource(if (g.ranked || ui.game?.players.orEmpty().size >= 4) R.string.game_lobby_title else R.string.game_lobby_practice), style = BType.display(30, 1.1f), color = c.ink, modifier = Modifier.semantics { heading() })
    Text(stringResource(R.string.game_lobby_body), style = BType.body(15, lineHeight = 1.5f), color = c.mut)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        g.players.sortedByDescending { it.userId == ui.me }.forEach { p ->
            Row(Modifier.fillMaxWidth().background(c.s1, RoundedCornerShape(14.dp)).border(1.dp, if (p.ready) c.ok else c.line, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp)
                .semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(p.name, 34.dp, c.accInk)
                Text(if (p.userId == ui.me) stringResource(R.string.game_you) else p.name, style = BType.body(14, FontWeight.Medium), color = c.ink, modifier = Modifier.weight(1f))
                if (p.ready) Icon(painterResource(R.drawable.ic_check_circle), stringResource(R.string.game_ready), tint = c.ok, modifier = Modifier.size(20.dp))
                else Text(stringResource(R.string.game_not_ready), style = BType.body(12), color = c.mut)
            }
        }
    }
    Text(stringResource(R.string.game_lobby_waiting, ui.secondsLeft), style = BType.body(12), color = c.mut)
    if (ui.ready) Text(stringResource(R.string.game_waiting_others), style = BType.body(14, FontWeight.Medium), color = c.ink2, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    else PrimaryButton(stringResource(R.string.game_get_role), actions.ready)
}

@Composable
private fun RoleCard(ui: GameUi, actions: GameActions) {
    val c = LocalBridgeColors.current
    Text(stringResource(R.string.game_private), style = BType.label(11f, 0.14f), color = c.mut, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    if (!ui.revealed || ui.role == null) {
        Column(Modifier.fillMaxWidth().height(380.dp).clip(RoundedCornerShape(28.dp)).background(c.bg).border(1.dp, c.accLine, RoundedCornerShape(28.dp))
            .clickable(enabled = ui.role != null, role = Role.Button, onClick = actions.reveal), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
            Icon(painterResource(R.drawable.ic_lock), null, tint = c.acc, modifier = Modifier.size(42.dp))
            Text(stringResource(R.string.game_reveal), style = BType.display(18), color = c.ink)
        }
    } else {
        val tone = if (ui.isBuilder) c.ok else c.bad
        Column(Modifier.fillMaxWidth().height(380.dp).background(c.s1, RoundedCornerShape(28.dp)).border(1.dp, tone, RoundedCornerShape(28.dp)).padding(28.dp).semantics(mergeDescendants = true) { },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
            Text(stringResource(R.string.game_you_are), style = BType.label(11f, 0.16f), color = c.mut)
            Text(stringResource(if (ui.isBuilder) R.string.game_builder else R.string.game_drifter), style = BType.display(46, 1f).copy(fontWeight = FontWeight.Bold), color = tone)
            Text(stringResource(if (ui.isBuilder) R.string.game_builder_text else R.string.game_drifter_text), style = BType.body(15, lineHeight = 1.5f), color = c.ink2, textAlign = TextAlign.Center)
        }
        if (ui.begun) Text(stringResource(R.string.game_waiting_others), style = BType.body(14, FontWeight.Medium), color = c.ink2, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        else PrimaryButton(stringResource(R.string.game_start_round), actions.begin)
    }
}

@Composable
private fun Round(ui: GameUi, g: GameDto, actions: GameActions) {
    val c = LocalBridgeColors.current
    Text(stringResource(if (ui.isBuilder) R.string.game_instr_builder else R.string.game_instr_drifter), style = BType.display(22), color = c.ink, modifier = Modifier.semantics { heading() })
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("0:%02d".format(ui.secondsLeft), style = BType.label(20f, 0f).copy(fontWeight = FontWeight.SemiBold), color = c.ink)
        Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.track)) {
            Box(Modifier.fillMaxWidth((ui.secondsLeft / 15f).coerceIn(0f, 1f)).height(4.dp).background(c.acc))
        }
    }
    Column(Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(20.dp)).border(1.dp, c.line, RoundedCornerShape(20.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        g.planks.chunked(4).forEachIndexed { row, planks ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                planks.forEachIndexed { i, p ->
                    val n = row * 4 + i
                    val cracked = p == "cracked"
                    val a11y = stringResource(if (cracked) R.string.game_plank_cracked else R.string.game_plank_solid, n + 1)
                    Column(Modifier.weight(1f).height(74.dp).clip(RoundedCornerShape(10.dp)).background(c.s1)
                        .then(if (cracked) Modifier.dashed(c.warn, 10.dp) else Modifier.border(1.5.dp, c.ink, RoundedCornerShape(10.dp)))
                        .clickable(role = Role.Button) { actions.act(n) }.semantics { contentDescription = a11y },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                        if (cracked) Icon(painterResource(R.drawable.ic_warning), null, tint = c.warn, modifier = Modifier.size(22.dp))
                        Text("${n + 1}", style = BType.label(12f, 0f).copy(fontWeight = FontWeight.SemiBold), color = if (cracked) c.warn else c.ink)
                    }
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Stat(stringResource(R.string.game_integrity), "${g.integrity}%", when { g.integrity >= 75 -> c.ok; g.integrity >= 50 -> c.warn; else -> c.bad }, Modifier.weight(1f))
        Stat(stringResource(R.string.game_you_label), stringResource(if (ui.isBuilder) R.string.game_repaired else R.string.game_loosened, ui.myActions), c.ink, Modifier.weight(1f))
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.game_sightings), style = BType.label(10f, 0.14f), color = c.mut)
        if (g.clues.isEmpty()) Text(stringResource(R.string.game_no_sightings_yet), style = BType.body(13), color = c.mut2)
        g.clues.reversed().take(4).forEach { Text(it.text, style = BType.body(13), color = c.ink2, modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color, modifier: Modifier) {
    val c = LocalBridgeColors.current
    Column(modifier.background(c.bg, RoundedCornerShape(14.dp)).padding(12.dp).semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = BType.label(9.5f, 0.12f), color = c.mut)
        Text(value, style = BType.display(22), color = color)
    }
}

@Composable
private fun Council(ui: GameUi, g: GameDto, actions: GameActions) {
    val c = LocalBridgeColors.current
    Text(stringResource(R.string.game_council), style = BType.display(26, 1.15f), color = c.ink, modifier = Modifier.semantics { heading() })
    Text(stringResource(R.string.game_council_body), style = BType.body(14, lineHeight = 1.5f), color = c.mut)
    g.players.filter { it.userId != ui.me }.forEach { p ->
        val sel = ui.choice == p.userId
        val clue = g.clues.lastOrNull { it.who == p.userId }?.text ?: stringResource(R.string.game_no_sightings)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (sel) c.accTint else c.s1).border(if (sel) 2.dp else 1.dp, if (sel) c.acc else c.line, RoundedCornerShape(16.dp))
            .clickable(enabled = !p.absent && !ui.voted, role = Role.RadioButton) { actions.choose(p.userId) }.semantics(mergeDescendants = true) { selected = sel }
            .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(p.name, 40.dp, c.accInk)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(p.name + if (p.absent) " · " + stringResource(R.string.game_absent) else "", style = BType.body(15, FontWeight.SemiBold), color = if (p.absent) c.mut else c.ink)
                Text(clue, style = BType.body(12), color = c.mut)
            }
            if (sel) Icon(painterResource(R.drawable.ic_check_circle), null, tint = c.acc, modifier = Modifier.size(22.dp))
        }
    }
    if (ui.voted) Text(stringResource(R.string.game_voted), style = BType.body(14, FontWeight.Medium), color = c.ink2, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    else PrimaryButton(stringResource(R.string.game_lock_vote), actions.lock, enabled = ui.choice != null)
}

@Composable
private fun Result(ui: GameUi, g: GameDto, actions: GameActions) {
    val c = LocalBridgeColors.current
    val me = g.players.find { it.userId == ui.me }
    val iWasDrifter = g.drifterId == ui.me
    val won = (g.buildersWon == true) != iWasDrifter
    val tone = if (won) c.ok else c.bad
    val name = { id: String? -> g.players.find { it.userId == id }?.name.orEmpty() }
    Column(Modifier.fillMaxWidth().background(c.s1, RoundedCornerShape(24.dp)).border(1.dp, tone, RoundedCornerShape(24.dp)).padding(horizontal = 20.dp, vertical = 28.dp)
        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(if (won) R.string.game_you_win else R.string.game_you_lose), style = BType.display(44, 1f).copy(fontWeight = FontWeight.Bold), color = tone)
        Text(if (iWasDrifter) stringResource(R.string.game_you_were_drifter) else stringResource(R.string.game_was_drifter, name(g.drifterId)), style = BType.display(18), color = c.ink, textAlign = TextAlign.Center)
        Text(when {
            g.ejectedId == null && g.buildersWon == true -> stringResource(R.string.game_drifter_left)
            g.ejectedId == null -> stringResource(R.string.game_ejected_none)
            g.ejectedId == ui.me -> stringResource(R.string.game_ejected_you)
            else -> stringResource(R.string.game_ejected, name(g.ejectedId))
        }, style = BType.body(14), color = c.mut, textAlign = TextAlign.Center)
    }
    if (g.tally.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.game_votes), style = BType.label(10f, 0.14f), color = c.mut)
        val total = g.players.size.coerceAtLeast(1)
        g.tally.entries.sortedByDescending { it.value }.forEach { (id, n) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.semantics(mergeDescendants = true) { }) {
                Text(if (id == ui.me) stringResource(R.string.game_you) else name(id), style = BType.body(13, FontWeight.Medium), color = c.ink2, modifier = Modifier.width(64.dp), maxLines = 1)
                Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.track)) {
                    Box(Modifier.fillMaxWidth(n / total.toFloat()).height(8.dp).background(if (id == g.drifterId) c.bad else c.mut))
                }
                Text("$n", style = BType.label(12f, 0f), color = c.ink)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f).background(c.accTint, RoundedCornerShape(14.dp)).border(1.dp, c.accLine, RoundedCornerShape(14.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.game_alliance_rating), style = BType.label(9.5f, 0.12f), color = c.mut)
            Text(if (g.ranked) stringResource(R.string.game_fun, me?.funPoints ?: 0) else stringResource(R.string.game_practice_fun), style = BType.display(if (g.ranked) 20 else 15), color = c.accInk)
        }
        Column(Modifier.weight(1f).background(c.bg, RoundedCornerShape(14.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.game_your_title), style = BType.label(9.5f, 0.12f), color = c.mut)
            Text(stringResource(when { g.mvpId == ui.me -> R.string.game_mvp; won -> R.string.game_sharp; else -> R.string.game_sport }), style = BType.display(16), color = c.ink)
        }
    }
    PrimaryButton(stringResource(R.string.game_share), actions.share)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton(stringResource(R.string.game_rematch), actions.rematch, Modifier.weight(1f))
        SecondaryButton(stringResource(R.string.game_done), actions.exit, Modifier.weight(1f))
    }
}
