package app.thebridge.feature.arcade

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.thebridge.R
import app.thebridge.data.MatchDto
import app.thebridge.ui.theme.BType
import app.thebridge.ui.theme.LocalBridgeColors
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// --- Darts ---

private val SECTORS = listOf(20, 1, 18, 4, 13, 6, 10, 15, 2, 17, 3, 19, 7, 16, 8, 11, 14, 9, 12, 5)
private val BoardDark = Color(0xFF1E1E1E)
private val BoardLight = Color(0xFFF1E6C8)
private val BoardRed = Color(0xFFC8102E)
private val BoardGreen = Color(0xFF00843D)

@Composable
fun DartsGame(m: MatchDto, v: DartsView, busy: Boolean, send: (JsonObject) -> Unit) {
    val c = LocalBridgeColors.current
    val me = v.players.indexOf(m.you)
    val them = m.opponent?.name.orEmpty()
    HeaderStrip(myName(m), "${v.remaining.getOrElse(me) { 301 }}", if (m.myTurn) stringResource(R.string.arc_darts_dart, v.darts.size + 1) else turnText(m),
        them, "${v.remaining.getOrElse(1 - me) { 301 }}", m.myTurn)
    val canThrow = m.myTurn && !busy
    val shown = if (v.darts.isNotEmpty()) v.darts else v.lastTurn?.darts.orEmpty()
    val throwAt by rememberUpdatedState<(Offset, Size) -> Unit> { p, s ->
        // Board units: the double ring's outside is 1, the drawing leaves room for the numbers.
        val r = s.minDimension / 2 / 1.18f
        val x = (p.x - s.width / 2) / r; val y = -(p.y - s.height / 2) / r
        if (canThrow) send(buildJsonObject { put("t", "throw"); put("x", x.toDouble()); put("y", y.toDouble()) })
    }
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val numberStyle = BType.label(11f, 0f).copy(color = Color.White)
    Canvas(Modifier.fillMaxWidth().padding(horizontal = 24.dp).aspectRatio(1f).semantics { contentDescription = shown.joinToString(", ") { it.label }.ifEmpty { "Dartboard" } }
        .pointerInput(Unit) { detectTapGestures { p -> throwAt(p, Size(size.width.toFloat(), size.height.toFloat())) } }) {
        val r = size.minDimension / 2 / 1.18f
        val o = center
        drawCircle(BoardDark, r * 1.18f, o)
        fun ring(r0: Float, r1: Float, a: Color, b: Color) {
            for (i in 0 until 20) {
                val start = -90f - 9f + i * 18f
                drawArc(if (i % 2 == 0) a else b, start, 18f, true, topLeft = Offset(o.x - r1, o.y - r1), size = Size(r1 * 2, r1 * 2))
            }
            drawCircle(BoardDark, r0, o)
        }
        ring(162f / 170 * r, r, BoardRed, BoardGreen)
        ring(107f / 170 * r, 162f / 170 * r, BoardDark, BoardLight)
        ring(99f / 170 * r, 107f / 170 * r, BoardRed, BoardGreen)
        ring(15.9f / 170 * r, 99f / 170 * r, BoardDark, BoardLight)
        drawCircle(BoardGreen, 15.9f / 170 * r, o)
        drawCircle(BoardRed, 6.35f / 170 * r, o)
        SECTORS.forEachIndexed { i, n ->
            val a = Math.toRadians(-90.0 + i * 18.0)
            val t = measurer.measure(n.toString(), numberStyle)
            drawText(t, topLeft = Offset(o.x + (cos(a) * r * 1.09).toFloat() - t.size.width / 2f, o.y + (sin(a) * r * 1.09).toFloat() - t.size.height / 2f))
        }
        shown.forEach { d ->
            val p = Offset(o.x + d.x.toFloat() * r, o.y - d.y.toFloat() * r)
            drawCircle(Color.White, 7.dp.toPx(), p)
            drawCircle(Color(0xFF23262B), 4.dp.toPx(), p)
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val turn = if (v.darts.isNotEmpty()) v.darts.joinToString("  ") { it.label } else v.lastTurn?.let { t -> (if (t.bust) stringResource(R.string.arc_darts_bust) + " · " else "") + t.darts.joinToString("  ") { it.label } } ?: "–"
        InfoTile(stringResource(if (v.darts.isNotEmpty()) R.string.arc_darts_this_turn else R.string.arc_darts_last_turn), turn, Modifier.weight(1f))
        val perk = m.you in v.perks && m.you !in v.perkUsed
        if (m.you in v.perks) InfoTile(stringResource(R.string.arc_streak_bonus), stringResource(if (perk) R.string.arc_darts_rethrow else R.string.arc_perk_used), Modifier.weight(1f), accent = perk,
            onClick = if (perk && canThrow && v.darts.isNotEmpty()) ({ send(buildJsonObject { put("t", "rethrow") }) }) else null)
    }
    Text(stringResource(R.string.arc_darts_hint), style = BType.body(12, lineHeight = 1.4f), color = c.mut, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
}

// --- Cup Pong ---

private const val CUP_R = 0.045f

@Composable
fun CupGame(m: MatchDto, v: CupView, busy: Boolean, send: (JsonObject) -> Unit) {
    val c = LocalBridgeColors.current
    val other = m.opponent
    val theirCups = v.cups[other?.id].orEmpty()
    val myCups = v.cups[m.you].orEmpty()
    HeaderStrip(myName(m), stringResource(R.string.arc_cup_left, myCups.size), if (m.myTurn) stringResource(R.string.arc_cup_throw, 3 - v.throwsLeft) else turnText(m),
        other?.name.orEmpty(), stringResource(R.string.arc_cup_left, theirCups.size), m.myTurn)
    val spots = v.spots[other?.id].orEmpty()
    var aim by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    val last = v.last?.takeIf { it.by == m.you }
    val frame = playback(last?.path?.let { p -> Recording(listOf(0), p.map { listOf(it[0], it[1]) }) }, v.turns.sum() * 2 + (2 - v.throwsLeft))
    val canThrow = m.myTurn && !busy
    val fire by rememberUpdatedState<(Float, Float) -> Unit> { a, p -> if (canThrow) send(buildJsonObject { put("t", "throw"); put("angle", a.toDouble()); put("power", p.toDouble()) }) }
    Canvas(Modifier.fillMaxWidth().padding(horizontal = 16.dp).aspectRatio(.62f).clip(RoundedCornerShape(20.dp))
        .semantics { contentDescription = "${theirCups.size}" }
        .pointerInput(Unit) {
            var start = Offset.Zero
            detectDragGestures(onDragStart = { start = it }, onDragEnd = { aim?.let { (a, p) -> fire(a, p) }; aim = null }, onDragCancel = { aim = null }) { change, _ ->
                val d = change.position - start
                // Swipe up to throw: sideways turns it, a longer swipe throws further.
                val angle = atan2(d.x, -d.y).coerceIn(-0.55f, 0.55f)
                val power = (hypot(d.x, d.y) / (size.height * .55f)).coerceIn(0f, 1f)
                aim = angle to power
            }
        }) {
        drawRect(Brush.verticalGradient(listOf(Surfaces.tableTop, Surfaces.tableBottom)))
        drawLine(Color.White.copy(alpha = .25f), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 2f)
        // Table units to the canvas: x -0.5…0.5 across, y 0 (thrower, bottom) to 2 (top).
        fun p(x: Double, y: Double) = Offset(((x + 0.5) * size.width).toFloat(), (size.height - y / 2.0 * size.height).toFloat())
        val cupPx = CUP_R * size.width
        spots.forEachIndexed { i, s ->
            val o = p(s[0], s[1])
            if (i in theirCups) { drawCircle(Surfaces.cup, cupPx, o); drawCircle(Surfaces.cupRim, cupPx, o, style = Stroke(3f)) }
            else drawCircle(Color.White.copy(alpha = .5f), cupPx, o, style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
        }
        aim?.let { (a, pw) ->
            val d = 0.9 + pw * 1.2
            val land = p(sin(a.toDouble()) * d, cos(a.toDouble()) * d)
            val from = p(0.0, 0.05)
            for (k in 1..12) { val t = k / 12f; drawCircle(Color.White.copy(alpha = .8f), 3.5f, from + (land - from) * t) }
            drawCircle(Color(0xFF8FD17A), cupPx * 1.3f, land, style = Stroke(3f))
        }
        val ball = last?.path?.getOrNull(frame)?.let { p(it[0], it[1]) } ?: p(0.0, 0.05)
        drawCircle(Color.White, size.width * .025f, ball)
    }
    Text(stringResource(R.string.arc_cup_hint), style = BType.body(12), color = c.mut, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val perk = m.you in v.perks && m.you !in v.perkUsed
        if (m.you in v.perks) InfoTile(stringResource(R.string.arc_streak_bonus), stringResource(if (perk) R.string.arc_cup_rerack else R.string.arc_perk_used), Modifier.weight(1f), accent = perk,
            onClick = if (perk && canThrow && v.throwsLeft == 2) ({ send(buildJsonObject { put("t", "rerack") }) }) else null)
        InfoTile(stringResource(R.string.arc_cup_next_label), if (m.myTurn) stringResource(R.string.arc_cup_next_them, other?.name.orEmpty()) else turnText(m), Modifier.weight(1f))
    }
    v.last?.let { t ->
        val who = if (t.by == m.you) stringResource(R.string.arc_you) else other?.name.orEmpty()
        Text(stringResource(if (t.cup != null) R.string.arc_cup_sank else if (t.rim) R.string.arc_cup_rim else R.string.arc_cup_missed, who), style = BType.body(13), color = c.ink,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

// --- 8-Ball ---

private val BallColors = mapOf(1 to Color(0xFFF2C230), 2 to Color(0xFF2456C8), 3 to Color(0xFFD9342B), 4 to Color(0xFF6A2C91), 5 to Color(0xFFF07F1B), 6 to Color(0xFF1F8A4C), 7 to Color(0xFF7A2420))
private fun ballColor(id: Int) = when (id) { 0 -> Color.White; 8 -> Color(0xFF151515); else -> BallColors[if (id > 8) id - 8 else id] ?: Color.Gray }

@Composable
fun PoolGame(m: MatchDto, v: PoolView, busy: Boolean, send: (JsonObject) -> Unit) {
    val c = LocalBridgeColors.current
    val other = m.opponent
    val mine = v.groups[m.you]
    val theirs = v.groups[other?.id]
    fun left(g: String?) = if (g == null) 7 else v.balls.count { !it.potted && (if (g == "solids") it.id in 1..7 else it.id in 9..15) }
    fun groupName(g: String?) = when (g) { "solids" -> R.string.arc_pool_solids; "stripes" -> R.string.arc_pool_stripes; else -> R.string.arc_pool_open }
    HeaderStrip(myName(m), "${7 - left(mine)}", turnText(m), other?.name.orEmpty(), "${7 - left(theirs)}", m.myTurn)
    val rec = v.last?.rec
    val frame = playback(rec, v.shots)
    var aim by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    val canShoot = m.myTurn && !busy && (rec == null || frame >= rec.frames.size - 1)
    val fire by rememberUpdatedState<(Float, Float) -> Unit> { a, p -> if (canShoot) send(buildJsonObject { put("angle", a.toDouble()); put("power", p.toDouble()) }) }
    val cue = v.balls.firstOrNull { it.id == 0 }
    Canvas(Modifier.fillMaxWidth().padding(horizontal = 12.dp).aspectRatio(1.9f).clip(RoundedCornerShape(16.dp))
        .semantics { contentDescription = "8-Ball" }
        .pointerInput(Unit) {
            var start = Offset.Zero
            detectDragGestures(onDragStart = { start = it }, onDragEnd = { aim?.let { (a, p) -> fire(a, p) }; aim = null }, onDragCancel = { aim = null }) { change, _ ->
                // Pull back from where you touched: the shot goes the other way, further pulls hit harder.
                val d = start - change.position
                if (hypot(d.x, d.y) > 8f) aim = atan2(d.y, d.x) to (hypot(d.x, d.y) / (size.width * .45f)).coerceIn(0f, 1f)
            }
        }) {
        drawRect(Surfaces.rail)
        val pad = size.height * .06f
        val w = size.width - pad * 2; val h = size.height - pad * 2
        drawRoundRect(Surfaces.felt, Offset(pad, pad), Size(w, h), CornerRadius(8f))
        fun p(x: Double, y: Double) = Offset(pad + (x / 2.0 * w).toFloat(), pad + (y * h).toFloat())
        val r = (0.03 / 2.0 * w).toFloat()
        listOf(0.0 to 0.0, 1.0 to 0.0, 2.0 to 0.0, 0.0 to 1.0, 1.0 to 1.0, 2.0 to 1.0).forEach { (x, y) -> drawCircle(Color.Black, r * 2.1f, p(x, y)) }
        val positions = v.balls.associate { it.id to (it.x to it.y) }.toMutableMap()
        val hidden = v.balls.filter { it.potted }.map { it.id }.toMutableSet()
        if (rec != null && frame < rec.frames.size - 1) {
            val f = rec.frames[frame]
            rec.ids.forEachIndexed { k, id ->
                positions[id] = f[k * 2] to f[k * 2 + 1]
                val gone = rec.goneAt[id.toString()]
                if (gone == null || frame < gone) hidden.remove(id)
            }
        }
        v.balls.forEach { b ->
            if (b.id in hidden) return@forEach
            val (x, y) = positions[b.id] ?: return@forEach
            ball(b.id, p(x, y), r)
        }
        val a = aim
        if (a != null && cue != null && !cue.potted) {
            val from = p(cue.x, cue.y)
            val dir = Offset(cos(a.first), sin(a.first))
            drawLine(Color.White.copy(alpha = .7f), from, from + dir * (w * (.15f + a.second * .5f)), 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        InfoTile(stringResource(R.string.arc_pool_you_have), stringResource(groupName(mine)), Modifier.weight(1f))
        val l = v.last
        val note = when {
            l == null -> stringResource(R.string.arc_pool_break)
            l.foul == "scratch" -> stringResource(R.string.arc_pool_scratch)
            l.foul == "no_hit" -> stringResource(R.string.arc_pool_no_hit)
            l.foul == "wrong_ball" -> stringResource(R.string.arc_pool_wrong)
            l.again -> stringResource(R.string.arc_pool_again)
            else -> stringResource(R.string.arc_pool_potted, l.potted.count { it != 0 })
        }
        InfoTile(stringResource(R.string.arc_pool_last_shot), note, Modifier.weight(1f))
    }
    if (m.done && v.how == "eight_early") Text(stringResource(R.string.arc_pool_eight_early), style = BType.body(13), color = c.mut, modifier = Modifier.padding(horizontal = 16.dp))
    Text(stringResource(R.string.arc_pool_hint), style = BType.body(12), color = c.mut, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
}

private fun DrawScope.ball(id: Int, at: Offset, r: Float) {
    if (id in 9..15) {
        drawCircle(Color.White, r, at)
        drawRect(ballColor(id), Offset(at.x - r, at.y - r * .5f), Size(r * 2, r))
        drawCircle(Color.Black.copy(alpha = .25f), r, at, style = Stroke(1f))
    } else drawCircle(ballColor(id), r, at)
    if (id == 0) drawCircle(Color.Black.copy(alpha = .2f), r, at, style = Stroke(1f))
}

