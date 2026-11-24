package app.thebridge.feature.arcade

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
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
import app.thebridge.data.MatchDto
import app.thebridge.data.PLAYABLE
import app.thebridge.data.RealtimeClient
import app.thebridge.data.remote.ApiException
import app.thebridge.feature.coach.CoachMascot
import app.thebridge.feature.coach.MascotMood
import app.thebridge.feature.game.gameName
import app.thebridge.nav.Match
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

data class MatchUi(
    val match: MatchDto? = null, val loading: Boolean = true, val failed: Boolean = false, val busy: Boolean = false, val connected: Boolean = true,
    /** Where to go next: a new match (rematch). */
    val goTo: String? = null, val now: Long = System.currentTimeMillis(), val offset: Long = 0,
) {
    val serverNow: Long get() = now + offset
}

@HiltViewModel
class MatchViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val arcade: ArcadeRepository,
    private val realtime: RealtimeClient,
    private val toaster: Toaster,
) : ViewModel() {
    private val id = saved.toRoute<Match>().matchId
    private val _state = MutableStateFlow(MatchUi())
    val state = _state.asStateFlow()

    init {
        realtime.acquire()
        viewModelScope.launch { realtime.events.collect(::handle) }
        viewModelScope.launch { realtime.connected.collect { up -> _state.update { it.copy(connected = up) }; if (up) watch() } }
        viewModelScope.launch { while (true) { delay(250); _state.update { it.copy(now = System.currentTimeMillis()) } } }
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        try { set(arcade.match(id)) } catch (e: Exception) { _state.update { it.copy(loading = false, failed = true) } }
    }

    private fun set(m: MatchDto) = _state.update { it.copy(match = m, loading = false, failed = false, busy = false, offset = if (m.now > 0) m.now - System.currentTimeMillis() else it.offset) }

    private fun watch() { realtime.send(buildJsonObject { put("type", "match:watch"); put("matchId", id) }) }

    private fun handle(e: JsonObject) {
        if (e["type"]?.jsonPrimitive?.content != "match:state") return
        val m = runCatching { arcade.json.decodeFromJsonElement(MatchDto.serializer(), e["match"]!!) }.getOrNull() ?: return
        if (m.id == id) set(m)
    }

    fun send(move: JsonObject) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try { set(arcade.move(id, move)) } catch (e: ApiException) {
                _state.update { it.copy(busy = false) }
                when (e.code) {
                    "stale", "not_your_turn", "game_over" -> load()
                    "column_full" -> toaster.show(UiText.Res(R.string.arc_column_full), ToastKind.Warn)
                    "slow_down" -> Unit
                    else -> toaster.show(UiText.Res(R.string.arc_move_failed), ToastKind.Warn)
                }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false) }
                toaster.show(UiText.Res(R.string.arc_needs_connection), ToastKind.Warn)
            }
        }
    }

    private fun act(call: suspend () -> MatchDto, then: (MatchDto) -> Unit = ::set) = viewModelScope.launch {
        try { then(call()) } catch (e: ApiException) {
            toaster.show(UiText.Res(if (e.code == "too_many_open") R.string.arc_too_many_open else R.string.arc_move_failed), ToastKind.Warn)
        } catch (e: Exception) { toaster.show(UiText.Res(R.string.arc_needs_connection), ToastKind.Warn) }
    }
    fun resign() = act({ arcade.resign(id) })
    fun claim() = act({ arcade.claim(id) })
    fun rematch() = act({ arcade.rematch(id) }) { m -> _state.update { it.copy(goTo = m.id) } }
    fun retry() = viewModelScope.launch { _state.update { it.copy(loading = true, failed = false) }; load() }
    fun consumed() = _state.update { it.copy(goTo = null) }

    override fun onCleared() {
        realtime.send(buildJsonObject { put("type", "match:leave"); put("matchId", id) })
        realtime.release()
    }
}

data class MatchActions(val close: () -> Unit = {}, val send: (JsonObject) -> Unit = {}, val resign: () -> Unit = {}, val claim: () -> Unit = {},
    val rematch: () -> Unit = {}, val retry: () -> Unit = {}, val bracket: ((String) -> Unit)? = null)

@Composable
fun MatchRoute(onClose: () -> Unit, onMatch: (String) -> Unit, onBracket: (String) -> Unit, vm: MatchViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(ui.goTo) { ui.goTo?.let { vm.consumed(); onMatch(it) } }
    MatchScreen(ui, MatchActions(onClose, vm::send, vm::resign, vm::claim, vm::rematch, { vm.retry() }, onBracket))
}

@Composable
fun turnText(m: MatchDto): String {
    val them = m.opponent?.name.orEmpty()
    return when {
        m.done -> stringResource(R.string.arc_over)
        m.myTurn -> stringResource(R.string.arc_your_turn)
        m.held -> stringResource(R.string.arc_held_short)
        m.turn == null -> stringResource(R.string.arc_both_turn)
        else -> stringResource(R.string.arc_their_turn, them)
    }
}

@Composable
fun MatchScreen(ui: MatchUi, actions: MatchActions) {
    val c = LocalBridgeColors.current
    val m = ui.match
    Column(Modifier.fillMaxSize().background(c.s1)) {
        val title = m?.let { stringResource(R.string.arc_top_vs, gameName(it.game), it.opponent?.name.orEmpty()) } ?: stringResource(R.string.arc_top)
        ArcadeTopBar(title, actions.close)
        if (!ui.connected) Text(stringResource(R.string.game_reconnecting), style = BType.body(12, FontWeight.Medium), color = c.warn,
            modifier = Modifier.fillMaxWidth().background(c.warnTint).padding(8.dp).semantics { liveRegion = LiveRegionMode.Polite }, textAlign = TextAlign.Center)
        when {
            m == null && ui.failed -> Notice(stringResource(R.string.arc_needs_connection), stringResource(R.string.retry), actions.retry)
            m == null -> Unit
            !m.known || m.game !in PLAYABLE -> Notice(stringResource(R.string.arc_update_to_play), null, {})
            else -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                GameBody(m, ui, actions)
                if (m.done) ResultPanel(m, actions) else MatchFooter(m, actions)
            }
        }
    }
}

@Composable
private fun GameBody(m: MatchDto, ui: MatchUi, actions: MatchActions) {
    val json = remember { kotlinx.serialization.json.Json { ignoreUnknownKeys = true; explicitNulls = false } }
    val v = m.view ?: return
    val busy = ui.busy || m.done
    when (m.game) {
        "four" -> FourGame(m, json.decodeFromJsonElement(FourView.serializer(), v), busy, actions.send)
        "word", "scramble" -> WordGame(m, json.decodeFromJsonElement(WordView.serializer(), v), ui.serverNow, ui.busy, actions.send)
        "darts" -> DartsGame(m, json.decodeFromJsonElement(DartsView.serializer(), v), busy, actions.send)
        "fleet" -> FleetGame(m, json.decodeFromJsonElement(FleetView.serializer(), v), busy, actions.send)
        "cup" -> CupGame(m, json.decodeFromJsonElement(CupView.serializer(), v), busy, actions.send)
        "pool" -> PoolGame(m, json.decodeFromJsonElement(PoolView.serializer(), v), busy, actions.send)
    }
}

@Composable
private fun Notice(text: String, button: String?, onClick: () -> Unit) {
    val c = LocalBridgeColors.current
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(text, style = BType.body(15, lineHeight = 1.5f), color = c.ink)
        if (button != null) SecondaryButton(button, onClick, Modifier.fillMaxWidth())
    }
}

/** Claim after 48 hours, and resigning (two taps). */
@Composable
private fun MatchFooter(m: MatchDto, actions: MatchActions) {
    val c = LocalBridgeColors.current
    var confirm by remember(m.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (m.held) Text(stringResource(R.string.arc_held_long), style = BType.body(13, lineHeight = 1.45f), color = c.mut)
        if (m.canClaim) {
            Text(stringResource(R.string.arc_claim_why, m.opponent?.name.orEmpty()), style = BType.body(13, lineHeight = 1.45f), color = c.mut)
            SecondaryButton(stringResource(R.string.arc_claim), actions.claim, Modifier.fillMaxWidth())
        }
        if (m.bracketId != null && actions.bracket != null) SecondaryButton(stringResource(R.string.arc_see_bracket), { actions.bracket.invoke(m.bracketId) }, Modifier.fillMaxWidth())
        Text(stringResource(if (confirm) R.string.arc_resign_confirm else R.string.arc_resign), style = BType.body(13, FontWeight.Medium), color = if (confirm) c.bad else c.mut,
            modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 44.dp).clickable(role = Role.Button) { if (confirm) actions.resign() else confirm = true }.padding(vertical = 12.dp))
    }
}

@Composable
private fun ResultPanel(m: MatchDto, actions: MatchActions) {
    val c = LocalBridgeColors.current
    val them = m.opponent?.name.orEmpty()
    val won = m.winnerId == m.you
    val title = when { m.winnerId == null -> stringResource(R.string.arc_draw); won -> stringResource(R.string.arc_you_won); else -> stringResource(R.string.arc_they_won, them) }
    val mine = m.scores[m.you] ?: 0
    val theirs = m.opponent?.let { m.scores[it.id] } ?: 0
    val how = when (m.result) {
        "resigned" -> stringResource(if (won) R.string.arc_how_they_resigned else R.string.arc_how_you_resigned, them)
        "claimed", "deadline" -> stringResource(R.string.arc_how_time)
        "left" -> stringResource(R.string.arc_how_left, them)
        else -> null
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(c.s2, RoundedCornerShape(20.dp)).padding(18.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.arc_result).uppercase(), style = BType.label(10f, 0.14f), color = c.mut)
                Text(title, style = BType.display(26), color = c.ink, modifier = Modifier.semantics { heading() })
                if (m.scored) Text(stringResource(R.string.arc_score_line, mine, theirs), style = BType.label(14f, 0.04f), color = c.ink)
                how?.let { Text(it, style = BType.body(13), color = c.mut) }
            }
            CoachMascot(MascotMood.Happy, size = 64.dp)
        }
        if (m.bracketId != null && actions.bracket != null) PrimaryButton(stringResource(R.string.arc_see_bracket), { actions.bracket.invoke(m.bracketId) })
        else PrimaryButton(stringResource(R.string.game_rematch), actions.rematch)
        SecondaryButton(stringResource(R.string.game_done), actions.close, Modifier.fillMaxWidth())
    }
}
