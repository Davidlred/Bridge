package app.thebridge.feature.game

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.thebridge.R
import app.thebridge.data.RealtimeClient
import app.thebridge.data.SocialRepository
import app.thebridge.data.remote.ApiException
import app.thebridge.data.remote.WdRoundDto
import app.thebridge.data.remote.WdTaskDto
import app.thebridge.data.remote.WdYouDto
import app.thebridge.data.remote.WhoseDayDto
import app.thebridge.data.session.SessionManager
import app.thebridge.feature.coach.CoachMascot
import app.thebridge.feature.coach.MascotMood
import app.thebridge.feature.community.dashed
import app.thebridge.nav.WhoseDay
import app.thebridge.ui.Toaster
import app.thebridge.ui.components.PrimaryButton
import app.thebridge.ui.components.SecondaryButton
import app.thebridge.ui.components.ToastKind
import app.thebridge.ui.components.UiText
import app.thebridge.ui.theme.BType
import app.thebridge.ui.theme.LocalBridgeColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.inject.Inject

data class WdUi(
    val me: String? = null,
    val game: WhoseDayDto? = null,
    /** Told only to this player by the server. */
    val you: WdYouDto? = null,
    val choice: String? = null,
    val nextTapped: Int = -1,
    val notice: UiText? = null,
    val connected: Boolean = true,
    /** This device's clock and how far the server's is ahead of it. Stages and timers follow server time. */
    val now: Long = System.currentTimeMillis(),
    val offset: Long = 0,
) {
    val serverNow: Long get() = now + offset
    val round: WdRoundDto? get() = game?.round
    val isOwner: Boolean get() = you?.owner == true && you.round == game?.current
    val myGuess: String? get() = you?.guess?.guessId?.takeIf { you.round == game?.current }
    fun name(id: String?) = game?.players?.find { it.userId == id }?.name.orEmpty()
    fun secondsTo(at: Long) = ((at - serverNow + 999) / 1000).toInt().coerceAtLeast(0)
}

/** Whose Day?: the server runs the game; this screen sends intents and shows what the server says. */
@HiltViewModel
class WhoseDayViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val social: SocialRepository,
    private val realtime: RealtimeClient,
    session: SessionManager,
    private val toaster: Toaster,
) : ViewModel() {
    private val allianceId = saved.toRoute<WhoseDay>().allianceId
    private val _state = MutableStateFlow(WdUi(me = session.user.value?.id))
    val state = _state.asStateFlow()

    init {
        realtime.acquire()
        viewModelScope.launch { realtime.events.collect(::handle) }
        viewModelScope.launch {
            realtime.connected.collect { up ->
                _state.update { it.copy(connected = up) }
                if (up) frame("wd:join")                             // first connect and every reconnect resume from server state
            }
        }
        viewModelScope.launch { while (true) { delay(250); _state.update { it.copy(now = System.currentTimeMillis()) } } }
        viewModelScope.launch { open() }
    }

    private suspend fun open() {
        try { val g = social.openWhoseDay(allianceId); _state.update { WdUi(me = it.me, game = g, connected = it.connected, offset = g.now - System.currentTimeMillis()) }; frame("wd:join") }
        catch (e: ApiException) { _state.update { it.copy(notice = UiText.Res(if (e.code == "other_game_open") R.string.game_other_open else R.string.error_generic)) } }
        catch (e: Exception) { toaster.show(UiText.Res(R.string.error_offline), ToastKind.Warn) }
    }

    private fun frame(type: String, extra: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()) {
        val id = _state.value.game?.id ?: return
        realtime.send(JsonObject(buildJsonObject { put("type", type); put("gameId", id) } + extra))
    }

    private fun handle(e: JsonObject) {
        val type = e["type"]?.jsonPrimitive?.content ?: return
        when (type) {
            "wd:state" -> {
                val g = runCatching { social.json.decodeFromJsonElement(WhoseDayDto.serializer(), e["game"]!!) }.getOrNull() ?: return
                if (g.id != _state.value.game?.id) return
                _state.update {
                    val newRound = g.current != it.game?.current || (g.phase == "round" && it.game?.phase != "round")
                    it.copy(game = g, offset = g.now - System.currentTimeMillis(), choice = if (newRound) null else it.choice, notice = null)
                }
            }
            "wd:you" -> {
                val y = runCatching { social.json.decodeFromJsonElement(WdYouDto.serializer(), e) }.getOrNull() ?: return
                if (y.gameId == _state.value.game?.id) _state.update { it.copy(you = y) }
            }
            "wd:error" -> when (e["error"]?.jsonPrimitive?.content) {
                "game_over" -> _state.update { it.copy(notice = UiText.Res(R.string.game_over)) }
                "already_started" -> _state.update { it.copy(notice = UiText.Res(R.string.wd_in_progress)) }
                "need_players" -> toaster.show(UiText.Res(R.string.wd_need_players), ToastKind.Warn)
                "no_lists" -> toaster.show(UiText.Res(R.string.wd_no_lists), ToastKind.Warn)
            }
        }
    }

    fun start() = frame("wd:start")
    fun setPool(on: Boolean) { _state.update { s -> s.copy(you = s.you?.copy(pool = on)) }; frame("wd:pool", mapOf("on" to JsonPrimitive(on))) }
    fun choose(userId: String) { if (_state.value.myGuess == null) _state.update { it.copy(choice = userId) } }
    fun lock() { val t = _state.value.choice ?: return; frame("wd:guess", mapOf("target" to JsonPrimitive(t))) }
    fun next() { val r = _state.value.game?.current ?: return; _state.update { it.copy(nextTapped = r) }; frame("wd:next", mapOf("round" to JsonPrimitive(r))) }
    fun share() = viewModelScope.launch {
        val id = _state.value.game?.id ?: return@launch
        try { social.shareGame(allianceId, id); toaster.show(UiText.Res(R.string.toast_game_shared), ToastKind.Ok) }
        catch (e: Exception) { toaster.show(UiText.Res(R.string.error_offline), ToastKind.Warn) }
    }
    fun rematch() = viewModelScope.launch { open() }
    fun leave() = frame("wd:leave")

    override fun onCleared() { leave(); realtime.release() }
}

data class WdActions(
    val exit: () -> Unit = {}, val start: () -> Unit = {}, val pool: (Boolean) -> Unit = {}, val choose: (String) -> Unit = {}, val lock: () -> Unit = {},
    val next: () -> Unit = {}, val share: () -> Unit = {}, val rematch: () -> Unit = {},
)

@Composable
fun WhoseDayRoute(onExit: () -> Unit, vm: WhoseDayViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    WhoseDayScreen(ui, WdActions(exit = { vm.leave(); onExit() }, start = vm::start, pool = vm::setPool, choose = vm::choose, lock = vm::lock,
        next = vm::next, share = { vm.share() }, rematch = { vm.rematch() }))
}

@Composable
fun WhoseDayScreen(ui: WdUi, actions: WdActions) {
    val c = LocalBridgeColors.current
    Column(Modifier.fillMaxSize().background(c.s1)) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clickable(role = Role.Button, onClickLabel = stringResource(R.string.game_leave), onClick = actions.exit), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.game_leave), tint = c.ink, modifier = Modifier.size(24.dp))
            }
            Text(stringResource(R.string.wd_top), style = BType.label(10f, 0.14f), color = c.acc, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(44.dp))
        }
        if (!ui.connected) Text(stringResource(R.string.game_reconnecting), style = BType.body(12, FontWeight.Medium), color = c.warn,
            modifier = Modifier.fillMaxWidth().background(c.warnTint).padding(8.dp).semantics { liveRegion = LiveRegionMode.Polite }, textAlign = TextAlign.Center)
        if (!ui.connected && ui.game == null) Text(stringResource(R.string.wd_offline), style = BType.body(14), color = c.mut, modifier = Modifier.padding(20.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 36.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            ui.notice?.let { Text(it.resolve(), style = BType.body(14), color = c.mut) }
            val g = ui.game ?: return@Column
            when (g.phase) {
                "lobby" -> Lobby(ui, g, actions)
                "round" -> RoundView(ui, g, actions)
                "reveal" -> Reveal(ui, g, actions)
                "results" -> Results(ui, g, actions)
                else -> {
                    Text(stringResource(R.string.wd_cancelled), style = BType.body(15, lineHeight = 1.5f), color = c.mut)
                    SecondaryButton(stringResource(R.string.game_done), actions.exit)
                }
            }
        }
    }
}

// --- Lobby ---

@Composable
private fun Lobby(ui: WdUi, g: WhoseDayDto, actions: WdActions) {
    val c = LocalBridgeColors.current
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.wd_title), style = BType.display(30, 1.1f), color = c.ink, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.wd_sub, g.rounds), style = BType.body(15, lineHeight = 1.5f), color = c.mut)
        }
        CoachMascot(MascotMood.Talking, size = 92.dp)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.semantics(mergeDescendants = true) { }) {
        AvatarStack(g.players.map { it.name })
        if (g.done.eligible > 0) Text(stringResource(R.string.wd_done, g.done.finished, g.done.eligible), style = BType.label(10f, 0.14f), color = c.ok)
    }
    Column(Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.wd_how), style = BType.display(16), color = c.ink, modifier = Modifier.semantics { heading() })
        listOf(R.string.wd_how_1, R.string.wd_how_2, R.string.wd_how_3).forEachIndexed { i, s ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top, modifier = Modifier.semantics(mergeDescendants = true) { }) {
                Box(Modifier.size(24.dp).border(1.dp, c.line2, CircleShape), contentAlignment = Alignment.Center) { Text("${i + 1}", style = BType.label(11f, 0f), color = c.ink2) }
                Text(stringResource(s), style = BType.body(14, lineHeight = 1.45f), color = c.ink2, modifier = Modifier.weight(1f).padding(top = 2.dp))
            }
        }
    }
    PoolCard(ui, actions)
    val enough = g.players.count { !it.absent } >= 3
    PrimaryButton(if (enough) stringResource(R.string.wd_start, g.rounds) else stringResource(R.string.wd_need_players), actions.start, enabled = enough)
}

@Composable
private fun PoolCard(ui: WdUi, actions: WdActions) {
    val c = LocalBridgeColors.current
    val y = ui.you
    val on = y?.pool != false
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        app.thebridge.feature.community.SwitchRow(on, stringResource(R.string.wd_pool_title), stringResource(if (on) R.string.wd_pool_sub else R.string.wd_pool_off), actions.pool)
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp).alpha(if (on) 1f else .4f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                y == null -> Unit
                y.eligible == false || y.preview.isEmpty() -> Text(stringResource(R.string.wd_pool_not_done), style = BType.body(13), color = c.mut)
                else -> y.preview.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics(mergeDescendants = true) { }) {
                        Icon(painterResource(if (t.isPrivate) R.drawable.ic_lock else R.drawable.ic_check), null, tint = c.mut, modifier = Modifier.size(16.dp))
                        Text(t.name ?: stringResource(R.string.wd_private_task), style = BType.body(13), color = if (t.isPrivate) c.mut else c.ink2, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        t.durationMin?.let { Text(stringResource(R.string.wd_min, it), style = BType.label(11f, 0f), color = c.mut) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AvatarStack(names: List<String>) {
    val c = LocalBridgeColors.current
    Box(Modifier.height(32.dp).width((22 * names.size.coerceAtMost(7) + 10).dp)) {
        names.take(7).forEachIndexed { i, n ->
            Box(Modifier.offset(x = (22 * i).dp).size(32.dp).border(2.dp, c.s1, CircleShape).padding(2.dp).background(c.goalCards[i % c.goalCards.size], CircleShape), contentAlignment = Alignment.Center) {
                Text(n.take(1).uppercase(), style = BType.display(12, 1f), color = c.goalInk)
            }
        }
    }
}

@Composable
private fun Face(name: String, size: Dp, index: Int, ring: Color? = null) {
    val c = LocalBridgeColors.current
    Box(Modifier.size(size).then(if (ring != null) Modifier.border(3.dp, ring, CircleShape).padding(5.dp) else Modifier).background(c.goalCards[index % c.goalCards.size], CircleShape), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), style = BType.display((size.value * .36f).toInt(), 1f), color = c.goalInk)
    }
}

// --- Round ---

@Composable
private fun RoundView(ui: WdUi, g: WhoseDayDto, actions: WdActions) {
    val c = LocalBridgeColors.current
    val r = g.round ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.wd_round_title), style = BType.display(22), color = c.ink, modifier = Modifier.weight(1f).semantics { heading() })
        Text(stringResource(R.string.wd_round_of, r.index + 1, r.total), style = BType.label(10f, 0.14f), color = c.mut)
    }
    // Time left to guess; once guessing closes, the countdown to the reveal.
    val closed = r.stage >= 4
    val left = ui.secondsTo(if (closed) r.revealAt else r.closesAt)
    val span = if (closed) (r.revealAt - r.closesAt) else (r.closesAt - r.startsAt)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("0:%02d".format(left), style = BType.label(20f, 0f).copy(fontWeight = FontWeight.SemiBold), color = c.ink)
        Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.track)) {
            Box(Modifier.fillMaxWidth((((if (closed) r.revealAt else r.closesAt) - ui.serverNow).toFloat() / span.coerceAtLeast(1)).coerceIn(0f, 1f)).height(4.dp).background(c.acc))
        }
    }
    MysteryList(r)
    ClueLadder(r.stage, r.how)
    if (closed) Text(stringResource(R.string.wd_closed), style = BType.body(13), color = c.mut, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    if (ui.isOwner) {
        Column(Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(20.dp)).padding(vertical = 26.dp, horizontal = 20.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.wd_yours), style = BType.display(18), color = c.ink, textAlign = TextAlign.Center)
            Text(stringResource(R.string.wd_yours_count, r.guessed, r.guessers), style = BType.label(11f, 0.12f), color = c.mut)
        }
        return
    }
    val others = g.players.filter { it.userId != ui.me }
    val picked = ui.myGuess ?: ui.choice
    others.chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { p ->
                val sel = picked == p.userId
                val i = g.players.indexOf(p)
                Column(Modifier.weight(1f).heightIn(min = 92.dp).clip(RoundedCornerShape(16.dp)).background(if (sel) c.accTint else c.bg)
                    .border(if (sel) 2.dp else 1.dp, if (sel) c.ink2 else c.line, RoundedCornerShape(16.dp))
                    .clickable(enabled = ui.myGuess == null && !closed, role = Role.Button) { actions.choose(p.userId) }.semantics(mergeDescendants = true) { selected = sel }
                    .padding(vertical = 12.dp, horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Face(p.name, 40.dp, i)
                    Text(p.name, style = BType.body(13, FontWeight.Medium), color = if (p.absent) c.mut else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    val pts = listOf(3, 2, 1).getOrElse(r.stage - 1) { 0 }
    when {
        ui.myGuess != null -> {
            val waiting = (r.guessers - r.guessed).coerceAtLeast(0)
            Text(if (waiting > 0) stringResource(R.string.wd_locked_waiting, waiting) else stringResource(R.string.wd_locked_all), style = BType.body(14, FontWeight.Medium), color = c.ink2,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, textAlign = TextAlign.Center)
        }
        closed -> Unit
        ui.choice == null -> PrimaryButton(stringResource(R.string.wd_pick), {}, enabled = false)
        else -> PrimaryButton(if (pts == 1) stringResource(R.string.wd_lock_one, ui.name(ui.choice)) else stringResource(R.string.wd_lock, ui.name(ui.choice), pts), actions.lock)
    }
}

@Composable
private fun MysteryList(r: WdRoundDto) {
    val c = LocalBridgeColors.current
    Column(Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(20.dp)).border(1.dp, c.line, RoundedCornerShape(20.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (r.how && r.goalStreak != null) Text(stringResource(R.string.wd_goal_streak, r.goalStreak), style = BType.label(10f, 0.12f), color = c.mut, modifier = Modifier.padding(bottom = 2.dp))
        else if (r.stage >= 2 && r.finishedBy != null) {
            val by = clock(r.finishedBy).uppercase()
            Text(if ((r.minutes ?: 0) > 0) stringResource(R.string.wd_summary, r.count, r.minutes ?: 0, by) else stringResource(R.string.wd_summary_short, r.count, by),
                style = BType.label(10f, 0.12f), color = c.mut, modifier = Modifier.padding(bottom = 2.dp))
        }
        r.tasks.forEach { TaskRow(it) }
        r.goalHint?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp).semantics(mergeDescendants = true) { }) {
                Icon(painterResource(R.drawable.ic_flag), null, tint = c.ink2, modifier = Modifier.size(18.dp))
                Text(stringResource(if (r.how) R.string.wd_routine else R.string.wd_hint, it), style = BType.body(13, FontWeight.Medium), color = c.ink2)
            }
        }
    }
}

@Composable
private fun TaskRow(t: WdTaskDto) {
    val c = LocalBridgeColors.current
    val meta = listOfNotNull(t.durationMin?.let { stringResource(R.string.wd_min, it) }, t.doneAt?.let { clock(it) }, t.streak?.takeIf { it > 0 }?.let { stringResource(R.string.wd_streak, it) }).joinToString(" · ")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.s1)
        .then(if (t.isPrivate) Modifier.dashed(c.line2, 14.dp) else Modifier.border(1.dp, c.line, RoundedCornerShape(14.dp)))
        .padding(horizontal = 12.dp, vertical = 10.dp).semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(painterResource(if (t.isPrivate) R.drawable.ic_lock else R.drawable.ic_check_circle), null, tint = if (t.isPrivate) c.mut else c.ok, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(t.name ?: stringResource(R.string.wd_private_task), style = BType.body(14, FontWeight.Medium), color = if (t.isPrivate) c.mut else c.ink)
            if (meta.isNotEmpty()) Text(meta, style = BType.body(12), color = c.mut)
        }
        t.priority?.let { p ->
            val (fg, bg, label) = when (p) { "high" -> Triple(c.pHi, c.pHiTint, R.string.wd_prio_high); "low" -> Triple(c.pLo, c.pLoTint, R.string.wd_prio_low); else -> Triple(c.pMd, c.pMdTint, R.string.wd_prio_med) }
            Text(stringResource(label), style = BType.label(10f, 0.08f), color = fg, modifier = Modifier.background(bg, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}

/** Three clues: done ones muted with a check, the current one with a bolt, later ones locked. Stage changes are announced. */
@Composable
private fun ClueLadder(stage: Int, how: Boolean) {
    val c = LocalBridgeColors.current
    val labels = (if (how) listOf(R.string.wd_clue_how_1, R.string.wd_clue_how_2, R.string.wd_clue_how_3) else listOf(R.string.wd_clue_1, R.string.wd_clue_2, R.string.wd_clue_3)).map { stringResource(it) }
    val points = listOf(R.string.wd_pts_3, R.string.wd_pts_2, R.string.wd_pts_1).map { stringResource(it) }
    val announce = when (stage) { 2 -> stringResource(if (how) R.string.wd_stage_how_2 else R.string.wd_stage_2); 3 -> stringResource(if (how) R.string.wd_stage_how_3 else R.string.wd_stage_3); else -> "" }
    Row(Modifier.semantics { if (announce.isNotEmpty()) { liveRegion = LiveRegionMode.Polite; contentDescription = announce } }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, label ->
            val n = i + 1
            val state = when { n < stage -> 0; n == stage -> 1; else -> 2 }
            val desc = stringResource(when (state) { 0 -> R.string.wd_clue_done; 1 -> R.string.wd_clue_now; else -> R.string.wd_clue_locked }, label, points[i])
            Column(Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp))
                .background(if (state == 1) c.accTint else Color.Transparent).border(1.dp, if (state == 1) c.accLine else c.line, RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp).semantics(mergeDescendants = true) { contentDescription = desc }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(painterResource(when (state) { 0 -> R.drawable.ic_check; 1 -> R.drawable.ic_bolt; else -> R.drawable.ic_lock }), null, tint = if (state == 1) c.ink else c.mut, modifier = Modifier.size(13.dp))
                    Text(points[i], style = BType.label(9f, 0.1f), color = if (state == 1) c.ink else c.mut)
                }
                Text(label, style = BType.body(12, FontWeight.Medium), color = when (state) { 1 -> c.ink; 0 -> c.mut; else -> c.mut2 }, maxLines = 2)
            }
        }
    }
}

// --- Reveal ---

@Composable
private fun Reveal(ui: WdUi, g: WhoseDayDto, actions: WdActions) {
    val c = LocalBridgeColors.current
    val r = g.round ?: return
    val rv = r.reveal ?: return
    val mine = rv.ownerId == ui.me
    val ownerIndex = g.players.indexOfFirst { it.userId == rv.ownerId }.coerceAtLeast(0)
    Column(Modifier.fillMaxWidth().background(c.s1, RoundedCornerShape(24.dp)).border(1.5.dp, c.ok, RoundedCornerShape(24.dp)).padding(horizontal = 20.dp, vertical = 24.dp)
        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.wd_it_was), style = BType.label(10f, 0.16f), color = c.mut)
        Face(rv.ownerName, 84.dp, ownerIndex, ring = c.ok)
        Text(if (mine) stringResource(R.string.wd_your_day) else stringResource(R.string.wd_whose, rv.ownerName), style = BType.display(26), color = c.ink, textAlign = TextAlign.Center)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(if (rv.goalPrivate) R.drawable.ic_lock else R.drawable.ic_flag), null, tint = c.mut, modifier = Modifier.size(16.dp))
            val goal = if (rv.goalPrivate || rv.goalTitle == null) stringResource(R.string.wd_private_goal)
                else listOfNotNull(rv.goalTitle, rv.deadline?.let { stringResource(R.string.wd_by, date(it)) }).joinToString(" · ")
            Text(goal, style = BType.body(13), color = c.ink2)
        }
    }
    val me = rv.guesses.find { it.userId == ui.me }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val right = me?.correct == true
        Column(Modifier.weight(1f).background(if (right) c.okTint else c.bg, RoundedCornerShape(14.dp)).padding(14.dp).semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(when { mine -> R.string.wd_you_owner; right -> R.string.wd_you_right; me?.guessId == null -> R.string.wd_you_none; else -> R.string.wd_you_wrong }).uppercase(), style = BType.label(9.5f, 0.12f), color = c.mut)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (right) Icon(painterResource(R.drawable.ic_check_circle), null, tint = c.ok, modifier = Modifier.size(20.dp))
                Text(if (mine) "+${rv.ownerPoints}" else if (right) "+${me?.points ?: 0}" else "0", style = BType.display(22), color = if (right) c.ok else c.ink)
            }
        }
        Column(Modifier.weight(1f).background(c.bg, RoundedCornerShape(14.dp)).padding(14.dp).semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val owner = if (mine) stringResource(R.string.game_you) else rv.ownerName
            Text(stringResource(R.string.wd_fooled, owner, rv.ownerPoints).uppercase(), style = BType.label(9.5f, 0.12f), color = c.mut, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.wd_fooled_pts, rv.ownerPoints, owner), style = BType.display(18), color = c.ink)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.wd_guesses, rv.guesses.count { it.correct }, rv.guesses.size), style = BType.label(10f, 0.14f), color = c.mut)
        rv.guesses.forEach { x ->
            val who = if (x.userId == ui.me) stringResource(R.string.game_you) else ui.name(x.userId)
            Row(Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp).semantics(mergeDescendants = true) { },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Face(ui.name(x.userId), 30.dp, g.players.indexOfFirst { it.userId == x.userId }.coerceAtLeast(0))
                Text(if (x.guessId == null) stringResource(R.string.wd_said_nothing, who) else stringResource(R.string.wd_said, who, if (x.guessId == ui.me) stringResource(R.string.game_you) else ui.name(x.guessId)),
                    style = BType.body(13), color = c.ink2, modifier = Modifier.weight(1f))
                if (x.correct) Icon(painterResource(R.drawable.ic_check), null, tint = c.ok, modifier = Modifier.size(16.dp))
                Text(if (x.correct) "+${x.points}" else "0", style = BType.label(12f, 0f).copy(fontWeight = FontWeight.SemiBold), color = if (x.correct) c.ok else c.mut)
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.semantics(mergeDescendants = true) { }) {
        CoachMascot(MascotMood.Happy, size = 48.dp)
        Text(rv.arcLine, style = BType.body(14, lineHeight = 1.45f), color = c.ink, modifier = Modifier.weight(1f).background(c.bg, RoundedCornerShape(16.dp)).padding(12.dp))
    }
    val last = r.index + 1 >= r.total
    val tapped = ui.nextTapped == g.current
    PrimaryButton(if (last) stringResource(R.string.wd_see_results) else stringResource(R.string.wd_next, r.index + 2, r.total) + " · 0:%02d".format(ui.secondsTo(g.endsAt)),
        actions.next, enabled = !tapped)
}

// --- Results ---

@Composable
private fun Results(ui: WdUi, g: WhoseDayDto, actions: WdActions) {
    val c = LocalBridgeColors.current
    val res = g.results ?: return
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.wd_game_over), style = BType.display(30, 1.1f), color = c.ink, modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
            Text(stringResource(R.string.wd_played, g.allianceName, g.players.size), style = BType.body(14), color = c.mut)
        }
        CoachMascot(MascotMood.Happy, size = 80.dp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Award(stringResource(R.string.wd_sharpest), R.drawable.ic_visibility, res.sharpest?.let { ui.name(it.userId) },
            res.sharpest?.let { stringResource(R.string.wd_sharpest_why, it.right, it.of, it.early) }, Modifier.weight(1f))
        Award(stringResource(R.string.wd_dark_horse), R.drawable.ic_lock, res.darkHorse?.let { ui.name(it.userId) },
            res.darkHorse?.let { stringResource(if (it.lists == 1) R.string.wd_dark_horse_why else R.string.wd_dark_horse_why_n, it.fooled, it.lists) }, Modifier.weight(1f))
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.wd_scores), style = BType.label(10f, 0.14f), color = c.mut)
        res.scores.forEachIndexed { i, s ->
            val me = s.userId == ui.me
            Row(Modifier.fillMaxWidth().background(if (me) c.accTint else c.bg, RoundedCornerShape(14.dp)).then(if (me) Modifier.border(1.dp, c.accLine, RoundedCornerShape(14.dp)) else Modifier)
                .padding(horizontal = 12.dp, vertical = 10.dp).semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${i + 1}", style = BType.label(12f, 0f), color = c.mut, modifier = Modifier.width(16.dp))
                Face(ui.name(s.userId), 30.dp, g.players.indexOfFirst { it.userId == s.userId }.coerceAtLeast(0))
                Text(if (me) stringResource(R.string.game_you) else ui.name(s.userId), style = BType.body(14, FontWeight.Medium), color = c.ink, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.wd_pts, s.score), style = BType.label(13f, 0f).copy(fontWeight = FontWeight.SemiBold), color = c.ink)
            }
        }
    }
    res.favourite?.let { f ->
        Column(Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(20.dp)).padding(16.dp).semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.wd_favourite), style = BType.label(10f, 0.14f), color = c.mut)
            Text(f.task, style = BType.display(18), color = c.ink)
            val who = if (f.userId == ui.me) stringResource(R.string.game_you) else ui.name(f.userId)
            Text(if (f.streak >= 2) stringResource(R.string.wd_favourite_streak, who, f.streak) else who, style = BType.body(13), color = c.mut)
        }
    }
    PrimaryButton(stringResource(R.string.game_share), actions.share)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton(stringResource(R.string.game_rematch), actions.rematch, Modifier.weight(1f))
        SecondaryButton(stringResource(R.string.game_done), actions.exit, Modifier.weight(1f))
    }
}

@Composable
private fun Award(label: String, icon: Int, name: String?, why: String?, modifier: Modifier) {
    val c = LocalBridgeColors.current
    Column(modifier.heightIn(min = 132.dp).background(c.bg, RoundedCornerShape(18.dp)).border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(14.dp).semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(painterResource(icon), null, tint = c.ink2, modifier = Modifier.size(22.dp))
        Text(label, style = BType.label(9.5f, 0.12f), color = c.mut)
        Text(name ?: stringResource(R.string.wd_nobody), style = BType.display(18), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        why?.let { Text(it, style = BType.body(12, lineHeight = 1.4f), color = c.mut) }
    }
}

/** "07:40" (the owner's local time) in this device's time format. */
internal fun clock(hhmm: String): String = runCatching { LocalTime.parse(hhmm).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())) }.getOrDefault(hhmm)
internal fun date(iso: String): String = runCatching { LocalDate.parse(iso).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())) }.getOrDefault(iso)
