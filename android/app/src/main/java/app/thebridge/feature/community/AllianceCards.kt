package app.thebridge.feature.community

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebridge.R
import app.thebridge.data.remote.AllianceDto
import app.thebridge.data.remote.BoardDto
import app.thebridge.data.remote.GameStatusDto
import app.thebridge.data.remote.RatingDto
import app.thebridge.ui.components.BackButton
import app.thebridge.ui.theme.BType
import app.thebridge.ui.theme.BridgeColors
import app.thebridge.ui.theme.LocalBridgeColors

/** Gold / silver / bronze tier colour. */
fun BridgeColors.tier(t: String): Color = when (t) { "gold" -> accInk; "silver" -> ink2; else -> bronze }

@Composable
fun tierLabel(t: String) = stringResource(when (t) { "gold" -> R.string.tier_gold; "silver" -> R.string.tier_silver; else -> R.string.tier_bronze })

/** Game night card: open when every active member has finished today's tasks for this goal. Names who's left, never their tasks. */
@Composable
fun GameCard(a: AllianceDto, s: GameStatusDto?, goalLabel: String, onPlay: () -> Unit, onNudge: () -> Unit) {
    val c = LocalBridgeColors.current
    val open = s?.unlocked == true
    val status = when {
        s == null -> stringResource(R.string.game_card_locked)
        open && s.rankedToday -> stringResource(R.string.game_card_played)
        open && s.needsPlayers -> stringResource(R.string.game_card_open_practice)
        open -> stringResource(R.string.game_card_open)
        (s.meLeft ?: 0) > 0 -> stringResource(R.string.game_card_waiting_me, s.finished, s.eligible, s.meLeft ?: 0, goalLabel)
        else -> stringResource(R.string.game_card_waiting, s.finished, s.eligible, s.waiting.joinToString(", "))
    }
    Column(Modifier.fillMaxWidth().background(c.s1, RoundedCornerShape(20.dp)).border(1.dp, if (open) c.accLine else c.line, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.semantics(mergeDescendants = true) { }) {
            Box(Modifier.size(44.dp).background(c.accTint, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Icon(painterResource(if (open) R.drawable.ic_groups else R.drawable.ic_schedule), null, tint = c.acc, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.game_card_title), style = BType.display(16), color = c.ink)
                Text(status, style = BType.body(12, lineHeight = 1.4f), color = c.mut)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val label = if (open && !s!!.rankedToday && !s.needsPlayers) R.string.game_card_play else R.string.game_card_practice
            // Opens once everyone has finished; with fewer than 4 eligible (or after today's ranked game) it's a practice round.
            Box(Modifier.weight(1f).heightIn(min = 46.dp).clip(RoundedCornerShape(12.dp)).background(if (open) c.acc else c.track).clickable(enabled = open, role = Role.Button, onClick = onPlay), contentAlignment = Alignment.Center) {
                Text(stringResource(if (open) label else R.string.game_card_locked), style = BType.body(14, FontWeight.SemiBold), color = if (open) c.onAcc else c.mut, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
            }
            if (!open && s != null && s.waiting.isNotEmpty()) Box(Modifier.heightIn(min = 46.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, c.line, RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onNudge).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.game_nudge), style = BType.body(14, FontWeight.Medium), color = c.ink)
            }
        }
    }
}

/** Overall score in a tier ring, rank on the board, and the three parts. */
@Composable
fun RatingCard(r: RatingDto, onBoard: () -> Unit) {
    val c = LocalBridgeColors.current
    val tone = c.tier(r.tier)
    Column(Modifier.fillMaxWidth().background(c.s1, RoundedCornerShape(20.dp)).border(1.dp, c.line, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(64.dp).border(2.dp, tone, CircleShape), contentAlignment = Alignment.Center) { Text("${r.overall}", style = BType.display(22, 1f), color = tone) }
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.rating_tier, tierLabel(r.tier)), style = BType.label(10f, 0.14f), color = tone)
                Text(stringResource(R.string.rating_title), style = BType.display(15), color = c.ink)
                if (r.rank > 0) Text(stringResource(R.string.rating_rank, r.rank, r.of), style = BType.label(10f, 0.08f), color = c.mut)
            }
            Box(Modifier.size(44.dp).clip(CircleShape).border(1.dp, c.line, CircleShape).clickable(role = Role.Button, onClickLabel = stringResource(R.string.rating_open_board), onClick = onBoard), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_chevron_right), stringResource(R.string.rating_open_board), tint = c.ink, modifier = Modifier.size(20.dp))
            }
        }
        listOf(Triple(R.string.rating_productive, r.productivity, R.string.rating_productive_sub), Triple(R.string.rating_efficient, r.efficiency, R.string.rating_efficient_sub),
            Triple(R.string.rating_fun, r.funScore, R.string.rating_fun_sub)).forEach { (k, v, sub) ->
            Column(Modifier.semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(stringResource(k), style = BType.body(13, FontWeight.Medium), color = c.ink, modifier = Modifier.weight(1f))
                    Text("$v", style = BType.label(12f, 0f), color = c.ink2)
                }
                Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(c.track)) { Box(Modifier.fillMaxWidth(v / 100f).height(5.dp).background(c.acc)) }
                Text(stringResource(sub), style = BType.body(11), color = c.mut2)
            }
        }
    }
}

/** Weekly board for the alliance's goal category and size band. */
@Composable
fun RankingsScreen(board: BoardDto?, error: Boolean, onBack: () -> Unit, onRetry: () -> Unit) {
    val c = LocalBridgeColors.current
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(Modifier.padding(start = 10.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            BackButton(onBack)
            Text(stringResource(R.string.board_title), style = BType.display(22), color = c.ink, modifier = Modifier.semantics { heading() })
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.board_body), style = BType.body(13, lineHeight = 1.5f), color = c.mut, modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp))
            if (error && board == null) {
                Text(stringResource(R.string.board_error), style = BType.body(14, FontWeight.Medium), color = c.ink)
                Text(stringResource(R.string.retry), style = BType.body(14, FontWeight.SemiBold), color = c.ink,
                    modifier = Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onRetry).padding(horizontal = 18.dp, vertical = 12.dp))
            }
            board?.rows?.forEach { b ->
                val tone = c.tier(b.tier)
                Row(Modifier.fillMaxWidth().background(if (b.mine) c.accTint else c.s1, RoundedCornerShape(16.dp)).border(1.dp, if (b.mine) c.acc else c.line, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp).semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("#${b.rank}", style = BType.label(14f, 0f).copy(fontWeight = FontWeight.SemiBold), color = c.mut, modifier = Modifier.width(40.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(b.name, style = BType.body(15, FontWeight.SemiBold), color = c.ink)
                        Text(stringResource(R.string.board_members, b.members), style = BType.body(12), color = c.mut)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${b.score}", style = BType.display(20, 1f).copy(fontWeight = FontWeight.Bold), color = tone)
                        Text(tierLabel(b.tier), style = BType.label(9.5f, 0.1f), color = tone)
                    }
                }
            }
        }
    }
}
