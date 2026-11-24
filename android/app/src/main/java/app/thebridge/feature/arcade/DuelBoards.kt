package app.thebridge.feature.arcade

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.thebridge.R
import app.thebridge.data.MatchDto
import app.thebridge.ui.components.PrimaryButton
import app.thebridge.ui.components.SecondaryButton
import app.thebridge.ui.theme.BType
import app.thebridge.ui.theme.LocalBridgeColors
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

// --- Four in a Row ---

@Composable
fun FourGame(m: MatchDto, v: FourView, busy: Boolean, send: (JsonObject) -> Unit) {
    val c = LocalBridgeColors.current
    val me = v.players.indexOf(m.you)
    val them = m.opponent?.name.orEmpty()
    HeaderStrip(myName(m), discLabel(me), turnText(m), them, discLabel(1 - me), m.myTurn)
    val canPlay = m.myTurn && !busy
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(c.s2, RoundedCornerShape(20.dp)).padding(10.dp)) {
        Row(Modifier.fillMaxWidth()) {
            for (col in 0 until 7) {
                val free = (0 until 6).count { v.board.getOrElse(it * 7 + col) { 0 } == 0 }
                val label = stringResource(R.string.arc_four_column, col + 1, free)
                Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = canPlay && free > 0, role = Role.Button, onClickLabel = stringResource(R.string.arc_four_drop)) { send(buildJsonObject { put("col", col) }) }
                    .semantics(mergeDescendants = true) { contentDescription = label }
                    .padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (row in 0 until 6) {
                        val cell = row * 7 + col
                        Disc(v.board.getOrElse(cell) { 0 }, mine = me, win = cell in v.line, last = cell == v.last)
                    }
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        InfoTile(stringResource(R.string.arc_four_you_are), discLabel(me), Modifier.weight(1f))
        InfoTile(stringResource(R.string.arc_moves), "${v.moves} / 42", Modifier.weight(1f))
    }
    Text(stringResource(R.string.arc_four_hint), style = BType.body(13), color = c.mut, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
}

@Composable
private fun discLabel(i: Int) = stringResource(if (i == 0) R.string.arc_four_solid else R.string.arc_four_ring)

/** Player 1: a solid disc. Player 2: a ring. So the two never depend on colour alone. */
@Composable
private fun Disc(owner: Int, mine: Int, win: Boolean, last: Boolean) {
    val c = LocalBridgeColors.current
    Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(2.dp).clip(CircleShape).background(c.s1).then(if (win) Modifier.border(3.dp, c.ok, CircleShape) else Modifier), contentAlignment = Alignment.Center) {
        when (owner) {
            1 -> Box(Modifier.fillMaxSize(.8f).clip(CircleShape).background(c.acc))
            2 -> Box(Modifier.fillMaxSize(.8f).border(5.dp, c.ink, CircleShape))
        }
        if (last && owner != 0) Box(Modifier.size(6.dp).clip(CircleShape).background(if (owner == 1) c.onAcc else c.ink))
    }
}

// --- Word Dash and Scramble ---

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WordGame(m: MatchDto, v: WordView, serverNow: Long, busy: Boolean, send: (JsonObject) -> Unit) {
    val c = LocalBridgeColors.current
    val mine = v.rounds[m.you] ?: WordRound()
    val other = m.opponent
    val theirs = other?.let { v.rounds[it.id] } ?: WordRound()
    val dash = v.game == "word"
    val playing = mine.started && !mine.ended && m.myTurn
    val left = mine.endsAt?.let { ((it - serverNow + 999) / 1000).toInt().coerceIn(0, v.seconds) } ?: v.seconds
    val toBeat = if (theirs.ended) theirs.score else null
    // Out of time: close the round (the server would anyway, a little later).
    LaunchedEffect(playing, left == 0) { if (playing && left == 0) send(buildJsonObject { put("t", "end") }) }

    HeaderStrip(myName(m), "${mine.score}", turnText(m), other?.name.orEmpty(), if (theirs.ended || m.done) "${theirs.score}" else "–", m.myTurn)
    when {
        m.myTurn && !mine.started -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(c.s2, RoundedCornerShape(20.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(if (dash) R.string.arc_word_intro else R.string.arc_scramble_intro, v.seconds), style = BType.body(15, lineHeight = 1.5f), color = c.ink)
            toBeat?.let { Text(stringResource(R.string.arc_word_to_beat, other?.name.orEmpty(), it), style = BType.body(14, FontWeight.SemiBold), color = c.ink) }
            Text(stringResource(R.string.arc_word_plan_bonus), style = BType.body(13, lineHeight = 1.45f), color = c.mut)
            PrimaryButton(stringResource(R.string.arc_word_start), { send(buildJsonObject { put("t", "start") }) }, enabled = !busy)
        }
        playing -> WordPlay(v, mine, left, toBeat, other?.name.orEmpty(), dash, send)
        !m.done -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(c.s2, RoundedCornerShape(20.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.arc_word_waiting, other?.name.orEmpty(), mine.score), style = BType.body(15, lineHeight = 1.5f), color = c.ink)
            FoundList(mine.found)
        }
        else -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(pluralStringResource(R.plurals.arc_word_your_words, mine.found.size, mine.found.size).uppercase(), style = BType.label(10f, 0.12f), color = c.mut)
            FoundList(mine.found)
            Text(pluralStringResource(R.plurals.arc_word_their_words, theirs.found.size, other?.name.orEmpty(), theirs.found.size).uppercase(), style = BType.label(10f, 0.12f), color = c.mut)
            FoundList(theirs.found)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FoundList(found: List<FoundWord>) {
    val c = LocalBridgeColors.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        found.forEach { f ->
            Text("${f.w.uppercase()} +${f.pts}", style = BType.label(10f, 0.08f), color = c.ok, modifier = Modifier.background(c.okTint, RoundedCornerShape(10.dp)).padding(horizontal = 9.dp, vertical = 5.dp))
        }
    }
}

private fun touching(a: Int, b: Int) = a != b && kotlin.math.abs(a / 4 - b / 4) <= 1 && kotlin.math.abs(a % 4 - b % 4) <= 1

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordPlay(v: WordView, mine: WordRound, left: Int, toBeat: Int?, them: String, dash: Boolean, send: (JsonObject) -> Unit) {
    val c = LocalBridgeColors.current
    val path = remember { mutableStateListOf<Int>() }
    val submit: () -> Unit = {
        if (path.isNotEmpty()) {
            val p = path.toList()
            send(buildJsonObject { put("t", "word"); putJsonArray("path") { p.forEach { add(JsonPrimitive(it)) } } })
            path.clear()
        }
    }
    val current = path.joinToString("") { v.letters.getOrElse(it) { "" } }.uppercase()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoTile(stringResource(R.string.arc_you), "${mine.score}", Modifier.weight(1f))
            InfoTile(stringResource(R.string.arc_word_time), "%d:%02d".format(left / 60, left % 60), Modifier.weight(1f), accent = true)
            InfoTile(stringResource(R.string.arc_word_beat_label), toBeat?.let { "$them $it" } ?: "–", Modifier.weight(1f))
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.track)) {
            Box(Modifier.fillMaxWidth(left / v.seconds.toFloat()).height(6.dp).background(c.acc))
        }
        Text(current.ifEmpty { " " }.toCharArray().joinToString(" "), style = BType.display(30), color = c.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        val verdict = mine.last
        val feedback = when {
            verdict == null -> " "
            verdict.ok && verdict.bonus -> stringResource(R.string.arc_word_bonus, verdict.pts)
            verdict.ok -> stringResource(R.string.arc_word_points, verdict.pts)
            verdict.reason == "already_found" -> stringResource(R.string.arc_word_already)
            verdict.reason == "too_short" -> stringResource(R.string.arc_word_short)
            else -> stringResource(R.string.arc_word_not_word)
        }
        Text(feedback, style = BType.body(13, FontWeight.SemiBold), color = if (verdict?.ok == true) c.ok else c.mut, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
        if (dash) LetterGrid(v.letters, path, onSubmit = submit) else LetterRack(v.letters, path)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(stringResource(R.string.arc_word_clear), { path.clear() }, Modifier.weight(1f))
            PrimaryButton(stringResource(R.string.arc_word_submit), submit, Modifier.weight(1f), enabled = path.size >= 3)
        }
        Text(pluralStringResource(R.plurals.arc_word_found, mine.found.size, mine.found.size).uppercase(), style = BType.label(10f, 0.12f), color = c.mut)
        FoundList(mine.found.reversed())
        Text(stringResource(if (dash) R.string.arc_word_hint else R.string.arc_scramble_hint), style = BType.body(12), color = c.mut)
        TextButtonRow(stringResource(R.string.arc_word_finish)) { send(buildJsonObject { put("t", "end") }) }
    }
}

@Composable
private fun TextButtonRow(text: String, onClick: () -> Unit) {
    val c = LocalBridgeColors.current
    Text(text, style = BType.body(13, FontWeight.Medium), color = c.mut, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 12.dp))
}

/** 4×4: drag across touching letters (the word is sent on release), or tap them in order and press Submit. */
@Composable
private fun LetterGrid(letters: List<String>, path: MutableList<Int>, onSubmit: () -> Unit) {
    val c = LocalBridgeColors.current
    val density = LocalDensity.current
    val submit by rememberUpdatedState(onSubmit)
    BoxWithConstraints(Modifier.fillMaxWidth().background(c.s2, RoundedCornerShape(20.dp)).padding(10.dp)) {
        val cell = with(density) { (maxWidth / 4).toPx() }
        fun at(o: Offset): Int? {
            val col = (o.x / cell).toInt(); val row = (o.y / cell).toInt()
            if (col !in 0..3 || row !in 0..3) return null
            // Only the middle of a tile counts, so a diagonal drag doesn't clip its neighbours.
            val cx = (col + .5f) * cell; val cy = (row + .5f) * cell
            return if (kotlin.math.hypot(o.x - cx, o.y - cy) < cell * .42f) row * 4 + col else null
        }
        fun extend(i: Int?) { if (i != null && i !in path && (path.isEmpty() || touching(path.last(), i))) path.add(i) }
        Column(Modifier.fillMaxWidth().pointerInput(letters) {
            detectDragGestures(onDragStart = { path.clear(); extend(at(it)) }, onDragEnd = { if (path.size >= 3) submit() }, onDragCancel = { path.clear() }) { change, _ -> extend(at(change.position)) }
        }, verticalArrangement = Arrangement.spacedBy(0.dp)) {
            for (row in 0 until 4) Row {
                for (col in 0 until 4) {
                    val i = row * 4 + col
                    val on = i in path
                    val letter = letters.getOrElse(i) { "" }.uppercase()
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(4.dp).clip(RoundedCornerShape(16.dp)).background(if (on) c.acc else c.s1)
                        .clickable(role = Role.Button) { if (on && path.last() == i) path.removeAt(path.size - 1) else extend(i) }
                        .semantics { contentDescription = letter; selected = on }, contentAlignment = Alignment.Center) {
                        Text(letter, style = BType.display(30), color = if (on) c.onAcc else c.ink)
                    }
                }
            }
        }
    }
}

/** Scramble: six letters, tap them in order (each once). */
@Composable
private fun LetterRack(letters: List<String>, path: MutableList<Int>) {
    val c = LocalBridgeColors.current
    Row(Modifier.fillMaxWidth().background(c.s2, RoundedCornerShape(20.dp)).padding(8.dp)) {
        letters.forEachIndexed { i, l ->
            val on = i in path
            Box(Modifier.weight(1f).aspectRatio(.8f).padding(3.dp).clip(RoundedCornerShape(14.dp)).background(if (on) c.track else c.s1)
                .clickable(enabled = !on, role = Role.Button) { path.add(i) }.semantics { contentDescription = l; selected = on }, contentAlignment = Alignment.Center) {
                Text(l.uppercase(), style = BType.display(26), color = if (on) c.mut else c.ink)
            }
        }
    }
}

// --- Fleet Battle ---

@Composable
fun FleetGame(m: MatchDto, v: FleetView, busy: Boolean, send: (JsonObject) -> Unit) {
    val c = LocalBridgeColors.current
    val them = m.opponent?.name.orEmpty()
    val placed = v.placed[m.you] == true
    val sunkByMe = 5 - v.theirs.left
    val myShips = v.mine.ships.orEmpty()
    val myLeft = myShips.count { s -> !s.cells.all { it in v.mine.shots } }
    HeaderStrip(myName(m), stringResource(R.string.arc_fleet_afloat, myLeft), turnText(m), them, stringResource(R.string.arc_fleet_afloat, v.theirs.left), m.myTurn)
    if (!placed) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(c.s2, RoundedCornerShape(20.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.arc_fleet_intro), style = BType.body(15, lineHeight = 1.5f), color = c.ink)
            PrimaryButton(stringResource(R.string.arc_fleet_place), { send(buildJsonObject { put("t", "auto") }) }, enabled = !busy)
        }
        return
    }
    if (v.next == null && !m.done) Text(stringResource(R.string.arc_fleet_wait_place, them), style = BType.body(14), color = c.mut, modifier = Modifier.padding(horizontal = 16.dp))
    Text(stringResource(R.string.arc_fleet_their_waters, them).uppercase(), style = BType.label(10f, 0.12f), color = c.mut, modifier = Modifier.padding(horizontal = 16.dp))
    val fired = v.theirs.shots.associateBy { it.cell }
    val sunk = v.theirs.ships.flatMap { it.cells }.toSet()
    FleetGrid(Modifier.padding(horizontal = 16.dp)) { cell ->
        val s = fired[cell]
        val state = when { cell in sunk -> stringResource(R.string.arc_fleet_sunk); s?.hit == true -> stringResource(R.string.arc_fleet_hit); s != null -> stringResource(R.string.arc_fleet_miss); else -> null }
        FleetCell(cell, state, when { cell in sunk -> c.bad; s?.hit == true -> c.acc; s != null -> c.track; else -> c.s1 }, mark = s != null,
            onClick = if (m.myTurn && !busy && s == null) ({ send(buildJsonObject { put("t", "fire"); put("cell", cell) }) }) else null)
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        InfoTile(stringResource(R.string.arc_fleet_sunk_tile), "$sunkByMe / 5", Modifier.weight(1f))
        v.last?.let { l -> InfoTile(stringResource(R.string.arc_fleet_last), stringResource(when { l.sunk != null -> R.string.arc_fleet_sunk; l.hit -> R.string.arc_fleet_hit; else -> R.string.arc_fleet_miss }), Modifier.weight(1f)) }
    }
    Text(stringResource(R.string.arc_fleet_your_waters).uppercase(), style = BType.label(10f, 0.12f), color = c.mut, modifier = Modifier.padding(horizontal = 16.dp))
    val shipCells = myShips.flatMap { it.cells }.toSet()
    FleetGrid(Modifier.padding(horizontal = 56.dp)) { cell ->
        val hit = cell in v.mine.shots
        val ship = cell in shipCells
        FleetCell(cell, listOfNotNull(if (ship) stringResource(R.string.arc_fleet_ship) else null, if (hit) stringResource(if (ship) R.string.arc_fleet_hit else R.string.arc_fleet_miss) else null).joinToString(", ").ifEmpty { null },
            when { ship && hit -> c.bad; ship -> c.ink2; hit -> c.track; else -> c.s1 }, mark = hit, onClick = null)
    }
}

@Composable
private fun FleetGrid(modifier: Modifier, cell: @Composable (Int) -> Unit) {
    val c = LocalBridgeColors.current
    Column(modifier.fillMaxWidth().background(c.s2, RoundedCornerShape(16.dp)).padding(6.dp)) {
        for (row in 0 until 10) Row { for (col in 0 until 10) Box(Modifier.weight(1f)) { cell(row * 10 + col) } }
    }
}

@Composable
private fun FleetCell(cell: Int, state: String?, fill: Color, mark: Boolean, onClick: (() -> Unit)?) {
    val c = LocalBridgeColors.current
    val name = "${'A' + cell % 10}${cell / 10 + 1}"
    Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(1.dp).clip(RoundedCornerShape(4.dp)).background(fill)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.arc_fleet_fire), onClick = onClick) else Modifier)
        .semantics { contentDescription = listOfNotNull(name, state).joinToString(", ") }, contentAlignment = Alignment.Center) {
        if (mark) Box(Modifier.size(5.dp).clip(CircleShape).background(c.ink))
    }
}
