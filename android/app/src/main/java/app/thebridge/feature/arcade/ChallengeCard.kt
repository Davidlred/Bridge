package app.thebridge.feature.arcade

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebridge.R
import app.thebridge.data.MatchCardDto
import app.thebridge.data.PLAYABLE
import app.thebridge.data.remote.MemberDto
import app.thebridge.feature.community.Avatar
import app.thebridge.feature.game.gameIcon
import app.thebridge.feature.game.gameName
import app.thebridge.ui.components.BridgeSheet
import app.thebridge.ui.components.PrimaryButton
import app.thebridge.ui.components.SheetTitle
import app.thebridge.ui.theme.BType
import app.thebridge.ui.theme.LocalBridgeColors

/** The 1v1 games, with their icons, in line-up order (for the chat's "Start a game" sheet). */
val DuelGames = listOf("word" to "abc", "scramble" to "shuffle", "cup" to "local_drink", "pool" to "radio_button_checked", "darts" to "adjust", "four" to "view_column", "fleet" to "sailing")
private val tileFor = mapOf("word" to 2, "scramble" to 3, "cup" to 4, "pool" to 0, "darts" to 2, "four" to 4, "fleet" to 0)
fun iconFor(game: String) = DuelGames.firstOrNull { it.first == game }?.second ?: when (game) { "hoops" -> "sports_basketball"; "golf" -> "golf_course"; "push" -> "trip_origin"; "drift" -> "visibility_off"; "whose" -> "person_search"; else -> "" }

/**
 * A challenge in the alliance chat: about 270dp wide with an accent border. The player whose turn it is gets Play and
 * Later; everyone else sees how it stands.
 */
@Composable
fun ChallengeCard(card: MatchCardDto, me: String, onPlay: () -> Unit) {
    val c = LocalBridgeColors.current
    var later by remember(card.matchId, card.turn) { mutableStateOf(false) }
    val other = card.players.firstOrNull { it.id != me }
    val inIt = card.players.any { it.id == me }
    val turnName = card.players.firstOrNull { it.id == card.turn }?.name.orEmpty()
    val winner = card.players.firstOrNull { it.id == card.winnerId }
    val myTurn = card.status != "done" && card.turn == me
    val status = when {
        card.status == "done" && winner != null && card.scored && card.result == "played" -> stringResource(R.string.arc_card_won_score, winner.name, card.scores[winner.id] ?: 0, card.players.firstOrNull { it.id != winner.id }?.let { card.scores[it.id] } ?: 0)
        card.status == "done" && winner != null -> stringResource(R.string.arc_card_won, winner.name)
        card.status == "done" -> stringResource(R.string.arc_card_draw)
        myTurn -> stringResource(R.string.arc_card_your_turn)
        card.turn == null -> stringResource(R.string.arc_card_both)
        else -> stringResource(R.string.arc_card_their_turn, turnName)
    }
    val title = when {
        card.status == "done" -> stringResource(R.string.arc_card_title_done, card.players.getOrNull(0)?.name.orEmpty(), card.players.getOrNull(1)?.name.orEmpty())
        myTurn && card.toBeat != null && other != null -> stringResource(R.string.arc_card_beat_it, other.name, card.toBeat)
        myTurn -> stringResource(R.string.arc_card_your_move, other?.name.orEmpty())
        card.held && inIt -> stringResource(R.string.arc_held_long)
        inIt -> stringResource(R.string.arc_card_waiting_on, turnName.ifEmpty { other?.name.orEmpty() })
        else -> stringResource(R.string.arc_card_title_done, card.players.getOrNull(0)?.name.orEmpty(), card.players.getOrNull(1)?.name.orEmpty())
    }
    Column(Modifier.widthIn(max = 270.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.s1).border(1.5.dp, c.accLine, RoundedCornerShape(18.dp))
        .then(if (inIt && !myTurn) Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.arc_open_match), onClick = onPlay) else Modifier)
        .semantics(mergeDescendants = true) { }) {
        Thumbnail(card.game)
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${gameName(card.game)} · $status".uppercase(), style = BType.label(9.5f, 0.12f), color = if (myTurn) c.acc else c.mut)
            Text(title, style = BType.body(15, FontWeight.SemiBold, lineHeight = 1.35f), color = c.ink)
            if (card.game !in PLAYABLE) Text(stringResource(R.string.arc_update_to_play), style = BType.body(12), color = c.mut)
            else if (myTurn && !later) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).background(c.acc).clickable(role = Role.Button, onClick = onPlay), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.arc_card_play), style = BType.body(14, FontWeight.SemiBold), color = c.onAcc)
                }
                Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, c.line, RoundedCornerShape(12.dp)).clickable(role = Role.Button) { later = true },
                    contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.arc_card_later), style = BType.body(14, FontWeight.Medium), color = c.ink)
                }
            }
        }
    }
}

/** Each game's preview strip: its icon on the game's tile colour, with a pattern from the game. */
@Composable
private fun Thumbnail(game: String) {
    val c = LocalBridgeColors.current
    val fill = PlayerFills[(tileFor[game] ?: 1) % PlayerFills.size]
    Row(Modifier.fillMaxWidth().height(64.dp).background(fill).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(painterResource(gameIcon(iconFor(game))), null, tint = TileInk, modifier = Modifier.size(28.dp))
        val pattern = when (game) { "word" -> "S T A R\nO N E W"; "scramble" -> "T R A I N S"; "four" -> "● ○ ● ○"; "darts" -> "T20 · D16"; "fleet" -> "A1 · J10"; "cup" -> "● ● ● ●"; "pool" -> "8"; else -> "" }
        Text(pattern, style = BType.label(12f, 0.2f), color = TileInk)
        Spacer(Modifier.weight(1f))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
}

/** "Start a game": pick a 1v1 game, then who to play. */
@Composable
fun ChallengeSheet(members: List<MemberDto>, onDismiss: () -> Unit, onChallenge: (game: String, opponentId: String) -> Unit) {
    val c = LocalBridgeColors.current
    var game by remember { mutableStateOf<String?>(null) }
    var who by remember { mutableStateOf<String?>(null) }
    BridgeSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SheetTitle(stringResource(R.string.arc_sheet_title))
            Text(stringResource(R.string.arc_sheet_pick_game).uppercase(), style = BType.label(10f, 0.12f), color = c.mut)
            DuelGames.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (id, icon) ->
                        val on = game == id
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (on) c.accTint else c.s2).border(if (on) 1.5.dp else 0.dp, if (on) c.accLine else c.s2, RoundedCornerShape(14.dp))
                            .clickable(role = Role.Button) { game = id }.semantics { selected = on }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(34.dp).background(PlayerFills[(tileFor[id] ?: 0) % PlayerFills.size], RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                                Icon(painterResource(gameIcon(icon)), null, tint = TileInk, modifier = Modifier.size(20.dp))
                            }
                            Text(gameName(id), style = BType.body(11, FontWeight.SemiBold, lineHeight = 1.2f), color = c.ink, maxLines = 2)
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Text(stringResource(R.string.arc_sheet_pick_who).uppercase(), style = BType.label(10f, 0.12f), color = c.mut)
            members.filter { !it.me }.forEach { m ->
                val on = who == m.userId
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(if (on) c.accTint else c.s2).clickable(role = Role.Button) { who = m.userId }
                    .semantics(mergeDescendants = true) { selected = on }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Avatar(m.name, 30.dp, c.ink)
                    Text(m.name, style = BType.body(15, FontWeight.Medium), color = c.ink, modifier = Modifier.weight(1f))
                    if (!m.finished) Text(stringResource(R.string.arc_sheet_not_done), style = BType.label(9f, 0.08f), color = c.mut)
                }
            }
            Spacer(Modifier.width(1.dp))
            PrimaryButton(stringResource(R.string.arc_sheet_send), { onChallenge(game!!, who!!) }, enabled = game != null && who != null)
            Spacer(Modifier.height(8.dp))
        }
    }
}
