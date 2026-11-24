package app.thebridge.feature.arcade

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.thebridge.R
import app.thebridge.data.ArcadeRepository
import app.thebridge.data.LiveGameDto
import app.thebridge.data.RealtimeClient
import app.thebridge.data.session.SessionManager
import app.thebridge.feature.coach.CoachMascot
import app.thebridge.feature.coach.MascotMood
import app.thebridge.feature.community.Avatar
import app.thebridge.feature.game.gameName
import app.thebridge.nav.LiveArcade
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// --- World views ---

@Serializable data class HoopsPlayer(val userId: String, val made: Int = 0, val shots: Int = 0, val hoopX: Double = 0.0)
@Serializable data class HoopsWorld(val startsAt: Long = 0, val endsAt: Long = 0, val players: List<HoopsPlayer> = emptyList())
@Serializable data class HoopsShot(val i: Int = 0, val hoopX: Double = 0.0, val land: List<Double> = emptyList(), val path: List<List<Double>> = emptyList(), val made: Boolean = false, val rim: Boolean = false, val next: Double = 0.0)

@Serializable data class Block(val x0: Double, val y0: Double, val x1: Double, val y1: Double)
@Serializable data class GolfHole(val cup: List<Double> = listOf(0.5, 1.8), val blocks: List<Block> = emptyList())
@Serializable data class Golfer(val userId: String, val hole: Int = 0, val strokes: List<Int> = emptyList(), val total: Int = 0, val ball: List<Double> = listOf(0.5, 0.15), val done: Boolean = false, val mulligan: Boolean = false)
@Serializable data class GolfWorld(val holes: List<GolfHole> = emptyList(), val cupR: Double = 0.055, val ballR: Double = 0.03, val endsAt: Long = 0, val perks: List<String> = emptyList(), val players: List<Golfer> = emptyList())
@Serializable data class GolfNext(val hole: Int = 0, val ball: List<Double> = emptyList())
@Serializable data class GolfPutt(val hole: Int = 0, val rec: Recording? = null, val sunk: Boolean = false, val picked: Boolean = false, val strokes: Int = 0, val next: GolfNext? = null, val done: Boolean = false)

@Serializable data class Puck(val userId: String, val x: Double = 0.0, val y: Double = 0.0, val out: Boolean = false, val aimed: Boolean = false)
@Serializable data class PushSlide(val round: Int = 0, val rec: Recording = Recording(), val out: List<String> = emptyList())
@Serializable data class PushWorld(val round: Int = 1, val maxRounds: Int = 10, val phase: String = "aim", val endsAt: Long = 0, val arena: Double = 1.0, val puckR: Double = 0.09,
    val pucks: List<Puck> = emptyList(), val last: PushSlide? = null)

data class LiveUi(
    val game: LiveGameDto? = null, val me: String = "", val loading: Boolean = true, val none: Boolean = false, val connected: Boolean = true,
    /** This player's last shot result (Hoops, Mini Golf), with a counter so the same result can replay. */
    val reply: JsonElement? = null, val replyN: Int = 0,
    val now: Long = System.currentTimeMillis(), val offset: Long = 0,
) {
    val serverNow get() = now + offset
}

@HiltViewModel
class LiveArcadeViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val arcade: ArcadeRepository,
    private val realtime: RealtimeClient,
    private val toaster: Toaster,
    session: SessionManager,
) : ViewModel() {
    private val allianceId = saved.toRoute<LiveArcade>().allianceId
    private val _state = MutableStateFlow(LiveUi(me = session.user.value?.id.orEmpty()))
    val state = _state.asStateFlow()

    init {
        realtime.acquire()
        viewModelScope.launch { realtime.events.collect(::handle) }
        viewModelScope.launch { realtime.connected.collect { up -> _state.update { it.copy(connected = up) }; if (up) join() } }
        viewModelScope.launch { while (true) { delay(200); _state.update { it.copy(now = System.currentTimeMillis()) } } }
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        try {
            val g = arcade.live(allianceId)
            _state.update { it.copy(game = g ?: it.game, loading = false, none = g == null && it.game == null, offset = g?.let { x -> x.now - System.currentTimeMillis() } ?: it.offset) }
            join()
        } catch (e: Exception) { _state.update { it.copy(loading = false) } }
    }

    private fun join() { val id = _state.value.game?.id ?: return; realtime.send(buildJsonObject { put("type", "arc:join"); put("gameId", id) }) }
    private fun frame(type: String, extra: JsonObject? = null) {
        val id = _state.value.game?.id ?: return
        realtime.send(buildJsonObject { put("type", type); put("gameId", id); extra?.let { put("move", it) } })
    }

    private fun handle(e: JsonObject) {
        val id = _state.value.game?.id
        when (e["type"]?.jsonPrimitive?.content) {
            "arc:state" -> {
                val g = runCatching { arcade.json.decodeFromJsonElement(LiveGameDto.serializer(), e["game"]!!) }.getOrNull() ?: return
                if (id != null && g.id != id) return
                _state.update { it.copy(game = g, offset = g.now - System.currentTimeMillis()) }
            }
            "arc:you" -> if (e["gameId"]?.jsonPrimitive?.content == id) _state.update { it.copy(reply = e["reply"], replyN = it.replyN + 1) }
            "arc:error" -> when (e["error"]?.jsonPrimitive?.content) {
                "game_over" -> viewModelScope.launch { load() }
                "need_players" -> toaster.show(UiText.Res(R.string.arc_live_need_more), ToastKind.Warn)
            }
        }
    }

    fun ready() = frame("arc:ready")
    fun start() = frame("arc:start")
    fun move(m: JsonObject) = frame("arc:move", m)

    override fun onCleared() { frame("arc:leave"); realtime.release() }
}

@Composable
fun LiveArcadeRoute(onClose: () -> Unit, vm: LiveArcadeViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    LiveArcadeScreen(ui, onClose, vm::ready, vm::start, vm::move)
}

private val liveJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

@Composable
fun LiveArcadeScreen(ui: LiveUi, onClose: () -> Unit, onReady: () -> Unit, onStart: () -> Unit, onMove: (JsonObject) -> Unit) {
    val c = LocalBridgeColors.current
    val g = ui.game
    Column(Modifier.fillMaxSize().background(c.s1)) {
        ArcadeTopBar(g?.let { stringResource(R.string.arc_top_game, gameName(it.game)) } ?: stringResource(R.string.arc_top), onClose)
        if (!ui.connected) Text(stringResource(R.string.game_reconnecting), style = BType.body(12, FontWeight.Medium), color = c.warn,
            modifier = Modifier.fillMaxWidth().background(c.warnTint).padding(8.dp).semantics { liveRegion = LiveRegionMode.Polite }, textAlign = TextAlign.Center)
        if (g == null) {
            if (ui.none) Text(stringResource(R.string.arc_live_none), style = BType.body(15), color = c.ink, modifier = Modifier.padding(24.dp))
            return@Column
        }
        val me = g.players.firstOrNull { it.userId == ui.me }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (g.phase) {
                "lobby" -> Lobby(g, ui, onReady, onStart)
                "playing" -> {
                    if (me?.watching != false) Text(stringResource(R.string.arc_live_watching), style = BType.body(13), color = c.mut, modifier = Modifier.padding(horizontal = 16.dp))
                    val w = g.world ?: return@Column
                    val play = me != null && !me.watching
                    when (g.game) {
                        "hoops" -> Hoops(liveJson.decodeFromJsonElement(HoopsWorld.serializer(), w), g, ui, play, onMove)
                        "golf" -> Golf(liveJson.decodeFromJsonElement(GolfWorld.serializer(), w), g, ui, play, onMove)
                        "push" -> Push(liveJson.decodeFromJsonElement(PushWorld.serializer(), w), g, ui, play, onMove)
                        else -> Text(stringResource(R.string.arc_update_to_play), style = BType.body(15), color = c.ink, modifier = Modifier.padding(24.dp))
                    }
                }
                "results" -> Results(g, ui, onClose)
                else -> Text(stringResource(R.string.arc_live_cancelled), style = BType.body(15), color = c.ink, modifier = Modifier.padding(24.dp))
            }
        }
    }
}

@Composable
private fun Lobby(g: LiveGameDto, ui: LiveUi, onReady: () -> Unit, onStart: () -> Unit) {
    val c = LocalBridgeColors.current
    val players = g.players.filter { !it.watching }
    val ready = players.count { it.ready && !it.absent }
    val me = g.players.firstOrNull { it.userId == ui.me }
    val left = ((g.endsAt - ui.serverNow) / 1000).coerceAtLeast(0)
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.arc_live_lobby).uppercase(), style = BType.label(10f, 0.14f), color = c.acc)
                Text(gameName(g.game), style = BType.display(30), color = c.ink, modifier = Modifier.semantics { heading() })
                Text(stringResource(when (g.game) { "hoops" -> R.string.arc_hoops_rules; "golf" -> R.string.arc_golf_rules; else -> R.string.arc_push_rules }), style = BType.body(14, lineHeight = 1.45f), color = c.mut)
            }
            CoachMascot(MascotMood.Happy, size = 64.dp)
        }
        Text(stringResource(R.string.arc_live_ready_count, ready, players.size, "%d:%02d".format(left / 60, left % 60)), style = BType.label(11f, 0.08f), color = c.mut,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        players.forEach { p ->
            Row(Modifier.fillMaxWidth().background(c.s2, RoundedCornerShape(14.dp)).padding(12.dp).semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Avatar(p.name, 30.dp, c.ink)
                Text(p.name, style = BType.body(15, FontWeight.Medium), color = c.ink, modifier = Modifier.weight(1f))
                Text(stringResource(if (p.ready) R.string.arc_live_is_ready else R.string.arc_live_not_ready).uppercase(), style = BType.label(9.5f, 0.1f), color = if (p.ready) c.ok else c.mut)
            }
        }
        if (me == null || me.watching) Text(stringResource(R.string.arc_live_watching), style = BType.body(13), color = c.mut)
        else if (!me.ready) PrimaryButton(stringResource(R.string.arc_live_ready), onReady)
        else if (ready >= g.min) SecondaryButton(stringResource(R.string.arc_live_start_now), onStart, Modifier.fillMaxWidth())
        else Text(stringResource(R.string.arc_live_waiting_for, g.min), style = BType.body(13), color = c.mut)
    }
}

/** Leaderboard rows while playing. */
@Composable
private fun Board(rows: List<Pair<String, String>>, me: String, names: Map<String, String>) {
    val c = LocalBridgeColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(c.s2, RoundedCornerShape(16.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { (id, v) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics(mergeDescendants = true) { }) {
                Text(if (id == me) stringResource(R.string.arc_you) else names[id].orEmpty(), style = BType.body(13, if (id == me) FontWeight.Bold else FontWeight.Medium), color = c.ink, modifier = Modifier.weight(1f))
                Text(v, style = BType.label(12f, 0.04f), color = c.ink)
            }
        }
    }
}

// --- Hoops ---

@Composable
private fun Hoops(w: HoopsWorld, g: LiveGameDto, ui: LiveUi, play: Boolean, onMove: (JsonObject) -> Unit) {
    val c = LocalBridgeColors.current
    val mine = w.players.firstOrNull { it.userId == ui.me }
    val shot = ui.reply?.let { runCatching { liveJson.decodeFromJsonElement(HoopsShot.serializer(), it) }.getOrNull() }
    val frame = playback(shot?.let { s -> Recording(listOf(0), s.path.map { listOf(it[0], it[1]) }) }, ui.replyN)
    val counting = ui.serverNow < w.startsAt
    val left = ((w.endsAt - ui.serverNow + 999) / 1000).coerceIn(0, 30)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        InfoTile(stringResource(R.string.arc_hoops_made), "${mine?.made ?: 0}", Modifier.weight(1f))
        InfoTile(stringResource(R.string.arc_word_time), if (counting) stringResource(R.string.arc_get_ready) else "0:%02d".format(left), Modifier.weight(1f), accent = true)
    }
    var aim by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    val fire by rememberUpdatedState<(Float, Float) -> Unit> { a, p -> if (play && !counting && left > 0) onMove(buildJsonObject { put("angle", a.toDouble()); put("power", p.toDouble()) }) }
    Canvas(Modifier.fillMaxWidth().padding(horizontal = 16.dp).aspectRatio(.75f).clip(RoundedCornerShape(20.dp)).semantics { contentDescription = "Hoops" }
        .pointerInput(Unit) {
            var start = Offset.Zero
            detectDragGestures(onDragStart = { start = it }, onDragEnd = { aim?.let { (a, p) -> fire(a, p) }; aim = null }, onDragCancel = { aim = null }) { ch, _ ->
                val d = ch.position - start
                aim = atan2(d.x, -d.y).coerceIn(-.75f, .75f) to (hypot(d.x, d.y) / (size.height * .6f)).coerceIn(0f, 1f)
            }
        }) {
        drawRect(Color(0xFFC98B4F))
        drawRect(Color(0xFFB57A42), Offset(0f, 0f), Size(size.width, size.height * .3f))
        // Court units: x -0.6…0.6 across, y 0 (bottom) to 1.4 (top).
        fun p(x: Double, y: Double) = Offset(((x + .6) / 1.2 * size.width).toFloat(), (size.height - y / 1.4 * size.height).toFloat())
        val hoop = p(mine?.hoopX ?: 0.0, 1.0)
        drawRect(Color.White, Offset(hoop.x - size.width * .12f, hoop.y - size.width * .16f), Size(size.width * .24f, size.width * .14f), style = Stroke(4f))
        drawCircle(Color(0xFFE2572B), size.width * .06f, hoop, style = Stroke(6f))
        aim?.let { (a, pw) ->
            val d = .5 + pw
            val land = p(sin(a.toDouble()) * d, cos(a.toDouble()) * d)
            val from = p(0.0, 0.05)
            for (k in 1..10) drawCircle(Color.White.copy(alpha = .85f), 4f, from + (land - from) * (k / 10f))
        }
        val pt = shot?.path?.getOrNull(frame)
        val ball = pt?.let { p(it[0], it[1]) } ?: p(0.0, 0.05)
        val lift = ((pt?.getOrNull(2) ?: 0.0) * size.width * .15).toFloat()
        drawCircle(Color(0xFFE2572B), size.width * .045f, ball - Offset(0f, lift))
    }
    Text(stringResource(R.string.arc_hoops_hint), style = BType.body(12), color = c.mut, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    shot?.let { Text(stringResource(if (it.made) R.string.arc_hoops_in else R.string.arc_hoops_miss), style = BType.body(14, FontWeight.SemiBold), color = if (it.made) c.ok else c.mut,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) }
    Board(w.players.sortedByDescending { it.made }.map { it.userId to "${it.made}" }, ui.me, g.players.associate { it.userId to it.name })
}

// --- Mini Golf ---

@Composable
private fun Golf(w: GolfWorld, g: LiveGameDto, ui: LiveUi, play: Boolean, onMove: (JsonObject) -> Unit) {
    val c = LocalBridgeColors.current
    val mine = w.players.firstOrNull { it.userId == ui.me }
    val putt = ui.reply?.let { runCatching { liveJson.decodeFromJsonElement(GolfPutt.serializer(), it) }.getOrNull() }
    val frame = playback(putt?.rec, ui.replyN)
    val replaying = putt?.rec != null && frame < (putt.rec.frames.size - 1)
    // While a putt plays back, draw the hole it was on.
    val holeIndex = if (replaying) putt!!.hole else mine?.hole ?: 0
    val hole = w.holes.getOrNull(holeIndex) ?: return
    val left = ((w.endsAt - ui.serverNow) / 1000).coerceAtLeast(0)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        InfoTile(stringResource(R.string.arc_golf_hole), "${holeIndex + 1} / ${w.holes.size}", Modifier.weight(1f))
        InfoTile(stringResource(R.string.arc_golf_strokes), "${mine?.strokes?.getOrNull(holeIndex) ?: 0} · ${stringResource(R.string.arc_golf_total, mine?.total ?: 0)}", Modifier.weight(1f))
        InfoTile(stringResource(R.string.arc_word_time), "%d:%02d".format(left / 60, left % 60), Modifier.weight(1f), accent = true)
    }
    var aim by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    val canPutt = play && mine?.done == false && !replaying
    val fire by rememberUpdatedState<(Float, Float) -> Unit> { a, p -> if (canPutt) onMove(buildJsonObject { put("angle", a.toDouble()); put("power", p.toDouble()) }) }
    Canvas(Modifier.fillMaxWidth().padding(horizontal = 40.dp).aspectRatio(.5f).clip(RoundedCornerShape(18.dp)).semantics { contentDescription = "Mini Golf" }
        .pointerInput(Unit) {
            var start = Offset.Zero
            detectDragGestures(onDragStart = { start = it }, onDragEnd = { aim?.let { (a, p) -> fire(a, p) }; aim = null }, onDragCancel = { aim = null }) { ch, _ ->
                // Pull back: the ball goes the other way. Course y runs up the screen.
                val d = start - ch.position
                if (hypot(d.x, d.y) > 8f) aim = atan2(d.x, -d.y) to (hypot(d.x, d.y) / (size.height * .5f)).coerceIn(0f, 1f)
            }
        }) {
        drawRect(Surfaces.green)
        fun p(x: Double, y: Double) = Offset((x * size.width).toFloat(), (size.height - y / 2.0 * size.height).toFloat())
        hole.blocks.forEach { b -> val tl = p(b.x0, b.y1); val br = p(b.x1, b.y0); drawRect(Surfaces.wall, tl, Size(br.x - tl.x, br.y - tl.y)) }
        drawCircle(Color(0xFF101810), (w.cupR * size.width).toFloat(), p(hole.cup[0], hole.cup[1]))
        val ballPos = if (replaying) putt!!.rec!!.frames[frame].let { p(it[0], it[1]) } else mine?.ball?.let { p(it[0], it[1]) } ?: p(.5, .15)
        aim?.let { (a, pw) ->
            val dir = Offset(sin(a), -cos(a))
            drawLine(Color.White.copy(alpha = .8f), ballPos, ballPos + dir * (size.height * (.08f + pw * .4f)), 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        }
        if (!(putt?.sunk == true && replaying && frame >= (putt.rec?.goneAt?.get("0") ?: Int.MAX_VALUE))) drawCircle(Color.White, (w.ballR * size.width).toFloat(), ballPos)
    }
    Text(stringResource(R.string.arc_golf_hint), style = BType.body(12), color = c.mut, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    putt?.takeIf { !replaying }?.let {
        Text(stringResource(when { it.sunk -> R.string.arc_golf_sunk; it.picked -> R.string.arc_golf_picked; else -> R.string.arc_golf_rolled }, it.strokes), style = BType.body(14, FontWeight.SemiBold),
            color = if (it.sunk) c.ok else c.mut, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
    }
    if (ui.me in w.perks) {
        val can = mine?.mulligan == false
        InfoTile(stringResource(R.string.arc_streak_bonus), stringResource(if (can) R.string.arc_golf_mulligan else R.string.arc_perk_used), Modifier.padding(horizontal = 16.dp).fillMaxWidth(), accent = can,
            onClick = if (can && play) ({ onMove(buildJsonObject { put("t", "mulligan") }) }) else null)
    }
    if (mine?.done == true) Text(stringResource(R.string.arc_golf_done, mine.total), style = BType.body(14), color = c.ink, modifier = Modifier.padding(horizontal = 16.dp))
    Board(w.players.sortedBy { it.total }.map { it.userId to stringResource(R.string.arc_golf_board_row, it.hole + 1, it.total) }, ui.me, g.players.associate { it.userId to it.name })
}

// --- Push Out ---

@Composable
private fun Push(w: PushWorld, g: LiveGameDto, ui: LiveUi, play: Boolean, onMove: (JsonObject) -> Unit) {
    val c = LocalBridgeColors.current
    val dark = c.bg.red < .5f
    val ice = if (dark) Surfaces.iceDark else Surfaces.iceLight
    val order = w.pucks.map { it.userId }
    val mine = w.pucks.firstOrNull { it.userId == ui.me }
    val sliding = w.phase == "slide" && w.last?.round == w.round
    val frame = playback(if (sliding) w.last?.rec else null, if (sliding) w.round else -w.round)
    val left = ((w.endsAt - ui.serverNow + 999) / 1000).coerceAtLeast(0)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        InfoTile(stringResource(R.string.arc_push_round), "${w.round} / ${w.maxRounds}", Modifier.weight(1f))
        InfoTile(stringResource(R.string.arc_push_left_label), "${w.pucks.count { !it.out }}", Modifier.weight(1f))
        InfoTile(stringResource(if (w.phase == "aim") R.string.arc_push_aim else R.string.arc_push_slide), if (w.phase == "aim") "0:%02d".format(left) else "…", Modifier.weight(1f), accent = w.phase == "aim")
    }
    var aim by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    val canAim = play && mine?.out == false && w.phase == "aim"
    val fire by rememberUpdatedState<(Float, Float) -> Unit> { a, p -> if (canAim) onMove(buildJsonObject { put("angle", a.toDouble()); put("power", p.toDouble()) }) }
    Canvas(Modifier.fillMaxWidth().padding(horizontal = 16.dp).aspectRatio(1f).semantics { contentDescription = "Push Out" }
        .pointerInput(Unit) {
            var start = Offset.Zero
            detectDragGestures(onDragStart = { start = it }, onDragEnd = { aim?.let { (a, p) -> fire(a, p) }; aim = null }, onDragCancel = { aim = null }) { ch, _ ->
                // Flick: pull back from your puck, the puck goes the other way.
                val d = start - ch.position
                if (hypot(d.x, d.y) > 8f) aim = atan2(d.x, -d.y) to (hypot(d.x, d.y) / (size.width * .45f)).coerceIn(0f, 1f)
            }
        }) {
        val r = size.minDimension / 2 * .92f
        val o = center
        drawCircle(ice, r, o)
        drawCircle(if (dark) Color.White.copy(alpha = .25f) else Color(0xFF23262B).copy(alpha = .25f), r, o, style = Stroke(3f))
        fun p(x: Double, y: Double) = Offset(o.x + (x * r).toFloat(), o.y - (y * r).toFloat())
        val rec = w.last?.rec
        w.pucks.forEachIndexed { i, pk ->
            var x = pk.x; var y = pk.y; var out = pk.out
            if (sliding && rec != null && frame < rec.frames.size - 1) {
                val k = rec.ids.indexOf(i)
                if (k >= 0) { x = rec.frames[frame][k * 2]; y = rec.frames[frame][k * 2 + 1] }
                val gone = rec.goneAt[i.toString()]
                out = pk.out && gone != null && frame >= gone || (pk.out && i.toString() !in rec.goneAt)
            }
            if (out) return@forEachIndexed
            val at = p(x, y)
            val pr = (w.puckR * r).toFloat()
            drawCircle(PlayerFills[order.indexOf(pk.userId) % PlayerFills.size], pr, at)
            drawCircle(TileInk, pr, at, style = Stroke(if (pk.userId == ui.me) 6f else 2f))
            if (pk.aimed && w.phase == "aim") drawCircle(TileInk, pr * .25f, at)
        }
        val a = aim
        if (a != null && mine != null) {
            val from = p(mine.x, mine.y)
            drawLine(if (dark) Color.White else TileInk, from, from + Offset(sin(a.first), -cos(a.first)) * (r * (.15f + a.second * .6f)), 4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        }
    }
    Text(stringResource(if (mine?.out == true) R.string.arc_push_you_out else if (mine?.aimed == true && w.phase == "aim") R.string.arc_push_aimed else R.string.arc_push_hint),
        style = BType.body(13), color = c.mut, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
    Board(w.pucks.map { it.userId to stringResource(if (it.out) R.string.arc_push_out else R.string.arc_push_on) }, ui.me, g.players.associate { it.userId to it.name })
}

// --- Results ---

@Composable
private fun Results(g: LiveGameDto, ui: LiveUi, onClose: () -> Unit) {
    val c = LocalBridgeColors.current
    val rows = g.results.orEmpty()
    val winners = rows.filter { it.place == 1 }
    Column(Modifier.padding(horizontal = 20.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.arc_result).uppercase(), style = BType.label(10f, 0.14f), color = c.mut)
                Text(when { winners.size == 1 && winners[0].userId == ui.me -> stringResource(R.string.arc_you_won); winners.size == 1 -> stringResource(R.string.arc_they_won, winners[0].name)
                    else -> stringResource(R.string.arc_shared_win, winners.joinToString(", ") { it.name }) }, style = BType.display(26), color = c.ink, modifier = Modifier.semantics { heading() })
            }
            CoachMascot(MascotMood.Happy, size = 64.dp)
        }
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth().background(if (r.userId == ui.me) c.accTint else c.s2, RoundedCornerShape(14.dp))
                .then(if (r.userId == ui.me) Modifier.border(1.dp, c.accLine, RoundedCornerShape(14.dp)) else Modifier).padding(12.dp).semantics(mergeDescendants = true) { },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${r.place}", style = BType.display(18), color = c.ink)
                if (r.place == 1) Icon(painterResource(R.drawable.ic_game_emoji_events), stringResource(R.string.arc_first_place), tint = c.acc, modifier = Modifier.size(18.dp))
                Avatar(r.name, 28.dp, c.ink)
                Text(r.name, style = BType.body(15, FontWeight.Medium), color = c.ink, modifier = Modifier.weight(1f))
                Text(scoreText(g, r.score), style = BType.label(12f, 0.04f), color = c.ink)
            }
        }
        SecondaryButton(stringResource(R.string.game_done), onClose, Modifier.fillMaxWidth())
    }
}

@Composable
private fun scoreText(g: LiveGameDto, score: Int) = when (g.game) {
    "hoops" -> stringResource(R.string.arc_hoops_score, score)
    "golf" -> stringResource(R.string.arc_golf_score, score)
    else -> stringResource(if (score >= 900) R.string.arc_push_on else R.string.arc_push_out)
}

