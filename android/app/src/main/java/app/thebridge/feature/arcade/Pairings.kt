package app.thebridge.feature.arcade

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.thebridge.R
import app.thebridge.data.ArcadeRepository
import app.thebridge.data.BracketDto
import app.thebridge.data.BracketMatchDto
import app.thebridge.data.PersonDto
import app.thebridge.data.RealtimeClient
import app.thebridge.feature.coach.CoachMascot
import app.thebridge.feature.coach.MascotMood
import app.thebridge.feature.community.Avatar
import app.thebridge.feature.community.dashed
import app.thebridge.feature.game.gameName
import app.thebridge.nav.Pairings
import app.thebridge.data.session.SessionManager
import app.thebridge.ui.Toaster
import app.thebridge.ui.components.PrimaryButton
import app.thebridge.ui.components.SecondaryButton
import app.thebridge.ui.components.ToastKind
import app.thebridge.ui.components.UiText
import app.thebridge.ui.theme.BType
import app.thebridge.ui.theme.LocalBridgeColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

data class PairingsUi(val bracket: BracketDto? = null, val me: String = "", val loading: Boolean = true, val failed: Boolean = false, val goTo: String? = null)

@HiltViewModel
class PairingsViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val arcade: ArcadeRepository,
    private val realtime: RealtimeClient,
    private val toaster: Toaster,
    session: SessionManager,
) : ViewModel() {
    private val id = saved.toRoute<Pairings>().bracketId
    private val _state = MutableStateFlow(PairingsUi(me = session.user.value?.id.orEmpty()))
    val state = _state.asStateFlow()
    private var pending: Job? = null

    init {
        realtime.acquire()
        viewModelScope.launch { realtime.events.collect(::handle) }
        viewModelScope.launch { realtime.connected.collect { if (it) load() } }
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        try { val b = arcade.bracket(id); _state.update { it.copy(bracket = b, loading = false, failed = false) } }
        catch (e: Exception) { _state.update { it.copy(loading = false, failed = it.bracket == null) } }
    }

    /** Bracket events and card changes reload it, at most a few times a second. */
    private fun handle(e: JsonObject) {
        val type = e["type"]?.jsonPrimitive?.content ?: return
        val ours = when {
            type.startsWith("bracket:") -> e["bracketId"]?.jsonPrimitive?.content == id
            type == "match:card" -> runCatching { e["match"]!!.jsonObject["bracketId"]?.jsonPrimitive?.content }.getOrNull() == id
            else -> false
        }
        if (!ours) return
        pending?.cancel()
        pending = viewModelScope.launch { delay(300); load() }
    }

    fun start(matchId: String) = viewModelScope.launch {
        try { arcade.setMode(matchId, live = true); _state.update { it.copy(goTo = matchId) } }
        catch (e: Exception) { toaster.show(UiText.Res(R.string.arc_needs_connection), ToastKind.Warn) }
    }

    fun later(matchId: String) = viewModelScope.launch {
        try { arcade.setMode(matchId, live = false); toaster.show(UiText.Res(R.string.arc_later_done), ToastKind.Ok); load() }
        catch (e: Exception) { toaster.show(UiText.Res(R.string.arc_needs_connection), ToastKind.Warn) }
    }

    fun retry() = viewModelScope.launch { _state.update { it.copy(loading = true) }; load() }
    fun consumed() = _state.update { it.copy(goTo = null) }
    override fun onCleared() { realtime.release() }
}

@Composable
fun PairingsRoute(onClose: () -> Unit, onMatch: (String) -> Unit, vm: PairingsViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(ui.goTo) { ui.goTo?.let { vm.consumed(); onMatch(it) } }
    PairingsScreen(ui, onClose, onStart = { vm.start(it) }, onLater = { vm.later(it) }, onOpen = onMatch, onRetry = { vm.retry() })
}

@Composable
fun stageName(label: String) = stringResource(when (label) { "final" -> R.string.arc_stage_final; "semis" -> R.string.arc_stage_semis; "quarters" -> R.string.arc_stage_quarters; else -> R.string.arc_stage_round })

@Composable
fun PairingsScreen(ui: PairingsUi, onClose: () -> Unit, onStart: (String) -> Unit, onLater: (String) -> Unit, onOpen: (String) -> Unit, onRetry: () -> Unit) {
    val c = LocalBridgeColors.current
    val b = ui.bracket
    Column(Modifier.fillMaxSize().background(c.s1)) {
        ArcadeTopBar(b?.let { stringResource(R.string.arc_top_game, gameName(it.game)) } ?: stringResource(R.string.arc_top), onClose)
        if (b == null) {
            if (ui.failed) Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.arc_needs_connection), style = BType.body(15), color = c.ink)
                SecondaryButton(stringResource(R.string.retry), onRetry, Modifier.fillMaxWidth())
            }
            return@Column
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.arc_tonight_votes, b.votes, b.voters).uppercase(), style = BType.label(10f, 0.14f), color = c.acc)
                    Text(gameName(b.game), style = BType.display(30), color = c.ink, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.arc_paired), style = BType.body(14, lineHeight = 1.45f), color = c.mut)
                }
                CoachMascot(MascotMood.Happy, size = 72.dp)
            }
            b.champion?.let { Champion(it, b) }
            b.rounds.reversed().forEach { r ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.arc_round_label, r.round, stageName(r.label)).uppercase(), style = BType.label(10f, 0.14f), color = c.mut)
                    r.matches.forEach { m -> MatchRow(m, ui.me, b.round == r.round && b.status == "active", onOpen) }
                    r.bye?.user?.let { bye ->
                        val vs = r.bye.vs?.filterNotNull()
                        Text(if (vs != null && vs.size == 2) stringResource(R.string.arc_bye_vs, bye.name, vs[0].name, vs[1].name) else stringResource(R.string.arc_bye, bye.name),
                            style = BType.body(13, lineHeight = 1.4f), color = c.mut,
                            modifier = Modifier.fillMaxWidth().dashed(c.line2, 14.dp).padding(horizontal = 14.dp, vertical = 12.dp))
                    }
                }
            }
            StageStrip(b)
            val deadline = Instant.ofEpochMilli(b.deadline).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
            Text(stringResource(R.string.arc_deadline_rule, deadline), style = BType.body(12, lineHeight = 1.45f), color = c.mut)
        }
        val mine = b.rounds.flatMap { it.matches }.firstOrNull { it.id == b.myMatchId }
        if (b.status == "active" && mine != null && mine.status != "done") Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(stringResource(R.string.arc_start_match), { onStart(mine.id) })
            if (mine.mode == "duel_live") SecondaryButton(stringResource(R.string.arc_play_later), { onLater(mine.id) }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun MatchRow(m: BracketMatchDto, me: String, current: Boolean, onOpen: (String) -> Unit) {
    val c = LocalBridgeColors.current
    val status = when {
        m.status == "done" -> m.winnerId?.let { w -> stringResource(R.string.arc_row_done, (if (m.a?.id == w) m.a else m.b)?.name.orEmpty()) } ?: stringResource(R.string.arc_row_draw)
        m.mine && current -> stringResource(R.string.arc_row_now)
        m.status == "active" -> stringResource(R.string.arc_row_playing)
        else -> stringResource(R.string.arc_row_waiting)
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).background(if (m.mine) c.accTint else c.s2, RoundedCornerShape(14.dp))
        .then(if (m.mine) Modifier.border(1.dp, c.accLine, RoundedCornerShape(14.dp)) else Modifier)
        .then(if (m.mine) Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.arc_open_match)) { onOpen(m.id) } else Modifier)
        .padding(horizontal = 12.dp, vertical = 10.dp).semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Side(m.a, m.winnerId)
        Text(stringResource(R.string.arc_vs), style = BType.label(9f, 0.12f), color = c.mut)
        Side(m.b, m.winnerId, end = true)
        Text(status.uppercase(), style = BType.label(9.5f, 0.1f), color = if (m.mine && current && m.status != "done") c.acc else c.mut, maxLines = 2, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Side(p: PersonDto?, winner: String?, end: Boolean = false) {
    val c = LocalBridgeColors.current
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, if (end) Alignment.End else Alignment.Start)) {
        if (!end) Avatar(p?.name, 26.dp, c.ink)
        Text(p?.name.orEmpty(), style = BType.body(14, if (p?.id == winner) FontWeight.Bold else FontWeight.Medium), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (end) Avatar(p?.name, 26.dp, c.ink)
    }
}

/** Semis → Final → Alliance champ, the current stage marked. */
@Composable
private fun StageStrip(b: BracketDto) {
    val c = LocalBridgeColors.current
    Row(Modifier.fillMaxWidth().background(c.s2, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        b.stages.forEachIndexed { i, s ->
            val on = b.status == "active" && i + 1 == b.round
            Text(stageName(s), style = BType.body(12, if (on) FontWeight.Bold else FontWeight.Medium), color = if (on) c.ink else c.mut)
            Text("→", style = BType.body(12), color = c.mut2)
        }
        Icon(painterResource(R.drawable.ic_game_emoji_events), null, tint = if (b.status == "done") c.acc else c.mut, modifier = Modifier.size(16.dp))
        Text(stringResource(R.string.arc_champ), style = BType.body(12, if (b.status == "done") FontWeight.Bold else FontWeight.Medium), color = if (b.status == "done") c.ink else c.mut)
    }
}

@Composable
private fun Champion(p: PersonDto, b: BracketDto) {
    val c = LocalBridgeColors.current
    Row(Modifier.fillMaxWidth().background(c.accTint, RoundedCornerShape(20.dp)).border(1.dp, c.accLine, RoundedCornerShape(20.dp)).padding(16.dp)
        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(48.dp).background(c.s1, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_game_emoji_events), null, tint = c.acc, modifier = Modifier.size(28.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(R.string.arc_champ).uppercase(), style = BType.label(10f, 0.14f), color = c.mut)
            Text(p.name, style = BType.display(22), color = c.ink)
            Text(stringResource(R.string.arc_champ_sub, gameName(b.game)), style = BType.body(12), color = c.mut)
        }
    }
}
