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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
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
import app.thebridge.data.remote.NightDto
import app.thebridge.data.remote.NightGameDto
import app.thebridge.nav.GamePicker
import app.thebridge.ui.Toaster
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

enum class GameFilter { All, Everyone, Duel }

data class PickUi(
    val night: NightDto? = null,
    /** This member's vote, as the server last confirmed it (or the tap waiting for that). */
    val mine: String? = null,
    val filter: GameFilter = GameFilter.All,
    val notice: UiText? = null,
    val connected: Boolean = true,
    val loading: Boolean = true,
    /** Where to go next: "whose_day" or "drifting". */
    val goTo: String? = null,
    val now: Long = System.currentTimeMillis(),
    val offset: Long = 0,
) {
    val serverNow: Long get() = now + offset
    val secondsLeft: Int get() = night?.let { ((it.endsAt - serverNow + 999) / 1000).toInt().coerceIn(0, 99) } ?: 0
}

/** Where each game opens: the first two have their own screens, the other Everyone games share one, 1v1 games open the bracket. */
private fun kindOf(game: String, bracketId: String? = null) = when {
    bracketId != null -> "bracket:$bracketId"
    game == "whose" || game == "whose_day" -> "whose_day"
    game in setOf("hoops", "golf", "push") -> "live"
    else -> "drifting"
}

/** "Pick tonight's game": a 30-second vote run by the server. This screen sends votes and shows the tally. */
@HiltViewModel
class GamePickerViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val social: SocialRepository,
    private val realtime: RealtimeClient,
    private val toaster: Toaster,
) : ViewModel() {
    private val allianceId = saved.toRoute<GamePicker>().allianceId
    private val _state = MutableStateFlow(PickUi())
    val state = _state.asStateFlow()

    init {
        realtime.acquire()
        viewModelScope.launch { realtime.events.collect(::handle) }
        viewModelScope.launch { realtime.connected.collect { up -> _state.update { it.copy(connected = up) }; if (up) join() } }
        viewModelScope.launch { while (true) { delay(250); _state.update { it.copy(now = System.currentTimeMillis()) } } }
        viewModelScope.launch { open() }
    }

    private suspend fun open() {
        try {
            val n = social.openNight(allianceId)
            _state.update { it.copy(night = n, loading = false, offset = n.now - System.currentTimeMillis()) }
            join()
        } catch (e: ApiException) {
            when (e.code) {
                "other_game_open" -> _state.update { it.copy(goTo = kindOf(e.body["kind"]?.jsonPrimitive?.contentOrNull ?: "drifting")) }
                "need_players" -> _state.update { it.copy(loading = false, notice = UiText.Res(R.string.pick_need_players, listOf(runCatching { e.body["needs"]!!.jsonPrimitive.int }.getOrDefault(3)))) }
                else -> _state.update { it.copy(loading = false, notice = UiText.Res(R.string.error_generic)) }
            }
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, notice = UiText.Res(R.string.wd_offline)) }
        }
    }

    private fun join() { val id = _state.value.night?.id ?: return; realtime.send(buildJsonObject { put("type", "vote:join"); put("nightId", id) }) }

    private fun handle(e: JsonObject) {
        when (e["type"]?.jsonPrimitive?.content) {
            "vote:state" -> {
                val n = runCatching { social.json.decodeFromJsonElement(NightDto.serializer(), e["night"]!!) }.getOrNull() ?: return
                if (n.id != _state.value.night?.id) return
                _state.update { it.copy(night = n, offset = n.now - System.currentTimeMillis(), goTo = if (n.phase == "decided" && n.chosen != null) kindOf(n.chosen, n.bracketId) else it.goTo) }
            }
            "vote:you" -> if (e["nightId"]?.jsonPrimitive?.content == _state.value.night?.id) _state.update { it.copy(mine = e["game"]?.jsonPrimitive?.contentOrNull) }
            "vote:error" -> when (e["error"]?.jsonPrimitive?.content) {
                "closed" -> viewModelScope.launch { open() }                  // the vote ended while away: a game is running now, or start a new vote
                "not_votable" -> toaster.show(UiText.Res(R.string.pick_not_votable), ToastKind.Warn)
            }
        }
    }

    /** Tap a tile to vote; tap it again to take the vote back. */
    fun vote(game: String) {
        val n = _state.value.night ?: return
        if (n.phase != "voting") return
        val next = if (_state.value.mine == game) null else game
        _state.update { it.copy(mine = next) }
        realtime.send(buildJsonObject { put("type", "vote:cast"); put("nightId", n.id); put("game", next?.let(::JsonPrimitive) ?: JsonNull) })
    }

    fun filter(f: GameFilter) = _state.update { it.copy(filter = f) }
    fun retry() = viewModelScope.launch { _state.update { it.copy(loading = true, notice = null) }; open() }
    fun consumed() = _state.update { it.copy(goTo = null) }

    override fun onCleared() { realtime.release() }
}

@Composable
fun GamePickerRoute(onExit: () -> Unit, onGame: (kind: String) -> Unit, vm: GamePickerViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(ui.goTo) {
        val kind = ui.goTo ?: return@LaunchedEffect
        // Leave the result up for a moment so everyone sees which game won.
        if (ui.night?.phase == "decided") delay(1_200)
        vm.consumed(); onGame(kind)
    }
    GamePickerScreen(ui, onExit = onExit, onVote = vm::vote, onFilter = vm::filter, onRetry = { vm.retry() })
}

/** Tile fills from the avatar palette, cycled in line-up order. Icons and initials on them are always dark. */
private val TileFills = listOf(Color(0xFFFCE7D9), Color(0xFFE4F2D9), Color(0xFFDFECF6), Color(0xFFF7EBCD), Color(0xFFEDE4F7))
private val TileInk = Color(0xFF23262B)

@Composable
fun gameName(id: String): String = stringResource(when (id) {
    "drift" -> R.string.game_name_drift; "whose" -> R.string.game_name_whose; "word" -> R.string.game_name_word; "scramble" -> R.string.game_name_scramble
    "cup" -> R.string.game_name_cup; "pool" -> R.string.game_name_pool; "hoops" -> R.string.game_name_hoops; "darts" -> R.string.game_name_darts
    "golf" -> R.string.game_name_golf; "four" -> R.string.game_name_four; "fleet" -> R.string.game_name_fleet; "push" -> R.string.game_name_push
    else -> R.string.game_name_unknown
})

fun gameIcon(icon: String): Int = when (icon) {
    "visibility_off" -> R.drawable.ic_game_visibility_off; "person_search" -> R.drawable.ic_game_person_search; "abc" -> R.drawable.ic_game_abc
    "shuffle" -> R.drawable.ic_game_shuffle; "local_drink" -> R.drawable.ic_game_local_drink; "radio_button_checked" -> R.drawable.ic_game_radio_button_checked
    "sports_basketball" -> R.drawable.ic_game_sports_basketball; "adjust" -> R.drawable.ic_game_adjust; "golf_course" -> R.drawable.ic_game_golf_course
    "view_column" -> R.drawable.ic_game_view_column; "sailing" -> R.drawable.ic_game_sailing; "trip_origin" -> R.drawable.ic_game_trip_origin
    else -> R.drawable.ic_groups
}

@Composable
private fun modeTag(g: NightGameDto) = stringResource(when {
    g.mode == "everyone" -> R.string.pick_mode_everyone
    g.liveToo -> R.string.pick_mode_duel
    else -> R.string.pick_mode_duel_turns
})

@Composable
fun GamePickerScreen(ui: PickUi, onExit: () -> Unit, onVote: (String) -> Unit, onFilter: (GameFilter) -> Unit, onRetry: () -> Unit) {
    val c = LocalBridgeColors.current
    Column(Modifier.fillMaxSize().background(c.s1)) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clickable(role = Role.Button, onClickLabel = stringResource(R.string.game_leave), onClick = onExit), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.game_leave), tint = c.ink, modifier = Modifier.size(24.dp))
            }
            Text(stringResource(R.string.pick_top), style = BType.label(10f, 0.14f), color = c.acc, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(44.dp))
        }
        if (!ui.connected) Text(stringResource(R.string.game_reconnecting), style = BType.body(12, FontWeight.Medium), color = c.warn,
            modifier = Modifier.fillMaxWidth().background(c.warnTint).padding(8.dp).semantics { liveRegion = LiveRegionMode.Polite }, textAlign = TextAlign.Center)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.pick_title), style = BType.display(28), color = c.ink, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.pick_sub), style = BType.body(14, lineHeight = 1.45f), color = c.mut)
            }
            ui.notice?.let {
                Text(it.resolve(), style = BType.body(14, lineHeight = 1.5f), color = c.mut)
                SecondaryButton(stringResource(R.string.retry), onRetry, Modifier.fillMaxWidth())
            }
            val n = ui.night ?: return@Column
            Filters(ui.filter, n.games.size, onFilter)
            val shown = n.games.filter { when (ui.filter) { GameFilter.All -> true; GameFilter.Everyone -> it.mode == "everyone"; GameFilter.Duel -> it.mode != "everyone" } }
            shown.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { g -> Tile(g, n.counts[g.id] ?: 0, ui.mine == g.id, enabled = n.phase == "voting", Modifier.weight(1f)) { onVote(g.id) } }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        ui.night?.let { Footer(ui, it) }
    }
}

@Composable
private fun Filters(selected: GameFilter, all: Int, onFilter: (GameFilter) -> Unit) {
    val c = LocalBridgeColors.current
    Row(Modifier.fillMaxWidth().border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(GameFilter.All to stringResource(R.string.pick_filter_all, all), GameFilter.Everyone to stringResource(R.string.pick_filter_everyone), GameFilter.Duel to stringResource(R.string.pick_filter_duel)).forEach { (f, label) ->
            val on = f == selected
            Box(Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp)).background(if (on) c.s2 else Color.Transparent)
                .clickable(role = Role.Tab) { onFilter(f) }.semantics { this.selected = on }, contentAlignment = Alignment.Center) {
                Text(label, style = BType.body(13, if (on) FontWeight.SemiBold else FontWeight.Medium), color = if (on) c.ink else c.mut)
            }
        }
    }
}

@Composable
private fun Tile(g: NightGameDto, votes: Int, mine: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalBridgeColors.current
    val can = g.votable && enabled
    val name = gameName(g.id)
    val tag = modeTag(g)
    val state = when {
        !g.built -> stringResource(R.string.pick_soon)
        g.needs != null -> stringResource(R.string.pick_needs, g.needs)
        !g.available -> stringResource(R.string.pick_off)
        else -> null
    }
    val voteText = if (votes > 0) pluralStringResource(R.plurals.pick_votes, votes, votes) else ""
    val desc = listOfNotNull(name, tag, state, voteText.ifEmpty { null }, if (mine) stringResource(R.string.pick_your_vote) else null).joinToString(", ")
    Box(modifier
        .clip(RoundedCornerShape(16.dp))
        .background(if (mine) c.accTint else c.s1)
        .border(if (mine) 1.5.dp else 1.dp, if (mine) c.accLine else c.line, RoundedCornerShape(16.dp))
        .clickable(enabled = can, role = Role.Button, onClickLabel = stringResource(if (mine) R.string.pick_unvote else R.string.pick_vote)) { onClick() }
        .semantics(mergeDescendants = true) { contentDescription = desc; selected = mine }
        .padding(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 12.dp)) {
        Column(Modifier.fillMaxWidth().alpha(if (g.votable) 1f else .45f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(38.dp).background(TileFills[g.tile % TileFills.size], RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                Icon(painterResource(gameIcon(g.icon)), null, tint = TileInk, modifier = Modifier.size(22.dp))
            }
            Text(name, style = BType.body(13, FontWeight.SemiBold, lineHeight = 1.25f), color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(state?.uppercase() ?: tag, style = BType.label(9f, 0.1f), color = c.mut, maxLines = 2)
        }
        if (votes > 0) Box(Modifier.align(Alignment.TopEnd).clip(CircleShape).background(if (mine) c.ink else c.s2).padding(horizontal = 8.dp, vertical = 2.dp)) {
            Text("$votes", style = BType.label(11f, 0f), color = if (mine) c.s1 else c.ink)
        }
    }
}

@Composable
private fun Footer(ui: PickUi, n: NightDto) {
    val c = LocalBridgeColors.current
    val decided = n.phase == "decided"
    Column(Modifier.fillMaxWidth().background(c.s1).padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val s = ui.secondsLeft
            Text("0:%02d".format(s), style = BType.label(13f, 0.04f), color = c.ink)
            Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.track)) {
                Box(Modifier.fillMaxWidth(if (n.total > 0) n.voted / n.total.toFloat() else 0f).height(6.dp).background(c.acc))
            }
            Text(stringResource(R.string.pick_voted, n.voted, n.total), style = BType.body(12, FontWeight.Medium), color = c.mut)
        }
        val chosen = n.chosen
        val banner = when {
            decided && chosen != null -> stringResource(R.string.pick_chosen, gameName(chosen))
            decided -> stringResource(R.string.pick_none)
            n.tied != null -> stringResource(R.string.pick_tied, n.tied.votes)
            n.leader != null -> pluralStringResource(R.plurals.pick_leads, n.leader.votes, gameName(n.leader.game), n.leader.votes)
            else -> stringResource(R.string.pick_no_votes)
        }
        // Announced as it changes: the lead, a tie, and the winner.
        Text(banner, style = BType.body(14, FontWeight.Medium, lineHeight = 1.4f), color = c.ink,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.accTint).padding(horizontal = 14.dp, vertical = 12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite })
    }
}
