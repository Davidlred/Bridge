package app.thebridge.feature.arcade

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.thebridge.R
import app.thebridge.feature.community.Avatar
import app.thebridge.ui.theme.BType
import app.thebridge.ui.theme.LocalBridgeColors
import kotlinx.serialization.Serializable

// --- Game views, as the server sends them (each game's viewFor) ---

@Serializable data class FourView(val players: List<String> = emptyList(), val board: List<Int> = emptyList(), val next: Int = 0, val moves: Int = 0, val last: Int? = null,
    val winner: Int? = null, val draw: Boolean = false, val line: List<Int> = emptyList())

@Serializable data class FoundWord(val w: String, val pts: Int = 0, val bonus: Boolean = false)
@Serializable data class WordVerdict(val w: String, val ok: Boolean = false, val reason: String = "", val pts: Int = 0, val bonus: Boolean = false)
@Serializable data class WordRound(val started: Boolean = false, val endsAt: Long? = null, val ended: Boolean = false, val score: Int = 0, val count: Int = 0,
    val found: List<FoundWord> = emptyList(), val last: WordVerdict? = null)
@Serializable data class WordView(val game: String = "word", val letters: List<String> = emptyList(), val seconds: Int = 80, val players: List<String> = emptyList(),
    val rounds: Map<String, WordRound> = emptyMap())

@Serializable data class Dart(val x: Double, val y: Double, val score: Int = 0, val mult: Int = 1, val label: String = "")
@Serializable data class DartsTurn(val by: String, val darts: List<Dart> = emptyList(), val bust: Boolean = false)
@Serializable data class DartsView(val players: List<String> = emptyList(), val remaining: List<Int> = listOf(301, 301), val next: Int = 0, val darts: List<Dart> = emptyList(),
    val turns: List<Int> = listOf(0, 0), val bust: Boolean = false, val lastTurn: DartsTurn? = null, val winner: Int? = null, val draw: Boolean = false,
    val perks: List<String> = emptyList(), val perkUsed: List<String> = emptyList())

@Serializable data class Ship(val cells: List<Int> = emptyList())
@Serializable data class Shot(val cell: Int, val hit: Boolean = false)
@Serializable data class FleetMine(val ships: List<Ship>? = null, val shots: List<Int> = emptyList())
@Serializable data class FleetTheirs(val shots: List<Shot> = emptyList(), val ships: List<Ship> = emptyList(), val left: Int = 5)
@Serializable data class FleetLast(val by: String, val cell: Int, val hit: Boolean = false, val sunk: List<Int>? = null)
@Serializable data class FleetView(val players: List<String> = emptyList(), val next: Int? = null, val winner: Int? = null, val last: FleetLast? = null, val size: Int = 10,
    val fleet: List<Int> = listOf(5, 4, 3, 3, 2), val placed: Map<String, Boolean> = emptyMap(), val mine: FleetMine = FleetMine(), val theirs: FleetTheirs = FleetTheirs())

@Serializable data class CupThrow(val by: String, val angle: Double = 0.0, val power: Double = 0.0, val land: List<Double> = emptyList(), val cup: Int? = null, val rim: Boolean = false,
    val path: List<List<Double>> = emptyList())
@Serializable data class CupView(val players: List<String> = emptyList(), val cups: Map<String, List<Int>> = emptyMap(), val spots: Map<String, List<List<Double>>> = emptyMap(),
    val next: Int = 0, val throwsLeft: Int = 2, val turns: List<Int> = listOf(0, 0), val last: CupThrow? = null, val winner: Int? = null, val draw: Boolean = false,
    val perks: List<String> = emptyList(), val perkUsed: List<String> = emptyList())

@Serializable data class Recording(val ids: List<Int> = emptyList(), val frames: List<List<Double>> = emptyList(), val goneAt: Map<String, Int> = emptyMap())
@Serializable data class Ball(val id: Int, val x: Double, val y: Double, @kotlinx.serialization.SerialName("in") val potted: Boolean = false)
@Serializable data class PoolShot(val by: String, val rec: Recording = Recording(), val potted: List<Int> = emptyList(), val foul: String? = null, val again: Boolean = false)
@Serializable data class PoolView(val players: List<String> = emptyList(), val balls: List<Ball> = emptyList(), val groups: Map<String, String?> = emptyMap(), val next: Int = 0,
    val shots: Int = 0, val last: PoolShot? = null, val winner: Int? = null, val draw: Boolean = false, val how: String? = null)

/** Settings → Accessibility → Remove animations sets the animator scale to 0. */
@Composable
fun reduceMotion(): Boolean {
    val ctx = LocalContext.current
    return remember { runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false) }
}

/**
 * Plays a server recording (30 frames a second) once per [key]. Returns the frame to draw. With reduced motion it
 * shows the last frame straight away.
 */
@Composable
fun playback(rec: Recording?, key: Any?): Int {
    val last = (rec?.frames?.size ?: 1) - 1
    val still = reduceMotion()
    var frame by remember(key) { mutableIntStateOf(if (still) last else 0) }
    LaunchedEffect(key) {
        if (rec == null || still) { frame = last; return@LaunchedEffect }
        val start = withFrameNanos { it }
        while (frame < last) withFrameNanos { now -> frame = (((now - start) / 1_000_000_000.0) * 30).toInt().coerceAtMost(last) }
    }
    return frame.coerceIn(0, maxOf(0, last))
}

@Composable
fun ArcadeTopBar(label: String, onClose: () -> Unit, trailing: @Composable () -> Unit = { Spacer(Modifier.width(44.dp)) }) {
    val c = LocalBridgeColors.current
    Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clickable(role = Role.Button, onClickLabel = stringResource(R.string.game_leave), onClick = onClose), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.game_leave), tint = c.ink, modifier = Modifier.size(24.dp))
        }
        Text(label.uppercase(), style = BType.label(10f, 0.14f), color = c.acc, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

/** This player's own name in a match (for their avatar). */
fun myName(m: app.thebridge.data.MatchDto) = m.players.firstOrNull { it.id == m.you }?.name.orEmpty()

/** You · what's happening · them, across the top of every game. Turn changes are read out (a live region). */
@Composable
fun HeaderStrip(me: String, mine: String, middle: String, them: String, theirs: String, myTurn: Boolean) {
    val c = LocalBridgeColors.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(c.s2, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 10.dp).semantics(mergeDescendants = true) { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Avatar(me, 30.dp, c.ink)
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.arc_you).uppercase(), style = BType.label(9f, 0.12f), color = c.mut)
            Text(mine, style = BType.display(17), color = c.ink, maxLines = 1)
        }
        Text(middle.uppercase(), style = BType.label(9.5f, 0.1f), color = if (myTurn) c.acc else c.mut, textAlign = TextAlign.Center, modifier = Modifier.weight(1.3f), maxLines = 2)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(them.uppercase(), style = BType.label(9f, 0.12f), color = c.mut, maxLines = 1)
            Text(theirs, style = BType.display(17), color = c.ink, maxLines = 1)
        }
        Avatar(them, 30.dp, c.ink)
    }
}

@Composable
fun InfoTile(label: String, value: String, modifier: Modifier = Modifier, accent: Boolean = false, onClick: (() -> Unit)? = null) {
    val c = LocalBridgeColors.current
    Column(modifier.background(if (accent) c.accTint else c.s2, RoundedCornerShape(14.dp)).then(if (accent) Modifier.border(1.dp, c.accLine, RoundedCornerShape(14.dp)) else Modifier)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier).padding(12.dp).semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label.uppercase(), style = BType.label(9f, 0.12f), color = c.mut)
        Text(value, style = BType.body(13, FontWeight.SemiBold, lineHeight = 1.3f), color = c.ink)
    }
}

/** Surfaces drawn by the games themselves (felt, ice). Text never sits on them without its own backing. */
object Surfaces {
    val tableTop = Color(0xFF24564F)
    val tableBottom = Color(0xFF173A35)
    val felt = Color(0xFF1F5B45)
    val rail = Color(0xFF3B2A1E)
    val green = Color(0xFF2F6B45)
    val greenDark = Color(0xFF24543A)
    val wall = Color(0xFF6B4A2E)
    val iceLight = Color(0xFFDDEBF3)
    val iceDark = Color(0xFF1B2830)
    val cup = Color(0xFFD9473A)
    val cupRim = Color(0xFFF4F1EA)
}

/** The avatar fills, cycled by player order: discs, pucks and balls take them. */
val PlayerFills = listOf(Color(0xFFFCE7D9), Color(0xFFE4F2D9), Color(0xFFDFECF6), Color(0xFFF7EBCD), Color(0xFFEDE4F7))
val TileInk = Color(0xFF23262B)
