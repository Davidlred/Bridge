package app.thebridge.feature.community

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
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
import app.thebridge.data.SocialRepository
import app.thebridge.data.remote.BadgeDto
import app.thebridge.data.remote.BadgesDto
import app.thebridge.data.remote.BoardDto
import app.thebridge.nav.Rankings
import app.thebridge.ui.components.BackButton
import app.thebridge.ui.theme.BType
import app.thebridge.ui.theme.LocalBridgeColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// --- Rankings ---

@HiltViewModel
class RankingsViewModel @Inject constructor(saved: SavedStateHandle, private val social: SocialRepository) : ViewModel() {
    private val allianceId = saved.toRoute<Rankings>().allianceId
    val board = MutableStateFlow<BoardDto?>(null)
    val error = MutableStateFlow(false)
    init { load() }
    fun load() = viewModelScope.launch {
        error.value = false
        try { board.value = social.rankings(allianceId) } catch (e: Exception) { error.value = true }
    }
}

@Composable
fun RankingsRoute(onBack: () -> Unit, vm: RankingsViewModel = hiltViewModel()) {
    val board by vm.board.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    RankingsScreen(board, error, onBack) { vm.load() }
}

// --- Hall of Fame ---

@HiltViewModel
class HallViewModel @Inject constructor(private val social: SocialRepository) : ViewModel() {
    private val _state = MutableStateFlow<BadgesDto?>(null)
    val state = _state.asStateFlow()
    init { viewModelScope.launch { runCatching { social.badges() }.onSuccess { b -> _state.value = b } } }
    fun setPublic(on: Boolean) = viewModelScope.launch { _state.update { it?.copy(public = on) }; social.setBadgesPublic(on) }
}

@Composable
fun HallRoute(onBack: () -> Unit, vm: HallViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    HallOfFameScreen(s, onBack, vm::setPublic)
}

private data class BadgeDef(val key: String, val glyph: String, val name: Int, val hint: Int)
private val DEFS = listOf(
    BadgeDef("first", "I", R.string.badge_first, R.string.badge_first_hint), BadgeDef("s7", "7", R.string.badge_s7, R.string.badge_s7_hint),
    BadgeDef("s21", "21", R.string.badge_s21, R.string.badge_s21_hint), BadgeDef("arch", "A", R.string.badge_arch, R.string.badge_arch_hint),
    BadgeDef("s30", "30", R.string.badge_s30, R.string.badge_s30_hint), BadgeDef("week", "W", R.string.badge_week, R.string.badge_week_hint),
    BadgeDef("ref", "J", R.string.badge_ref, R.string.badge_ref_hint), BadgeDef("ally", "Δ", R.string.badge_ally, R.string.badge_ally_hint),
    BadgeDef("goal", "∞", R.string.badge_goal, R.string.badge_goal_hint),
)

/** Hexagon, as in the prototype's clip-path. */
private val Hex = GenericShape { size, _ ->
    moveTo(size.width / 2, 0f); lineTo(size.width, size.height * .25f); lineTo(size.width, size.height * .75f)
    lineTo(size.width / 2, size.height); lineTo(0f, size.height * .75f); lineTo(0f, size.height * .25f); close()
}

@Composable
fun HallOfFameScreen(s: BadgesDto?, onBack: () -> Unit, onPublic: (Boolean) -> Unit) {
    val c = LocalBridgeColors.current
    val byKey = s?.badges.orEmpty().associateBy { it.key }
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(Modifier.padding(start = 10.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            BackButton(onBack)
            Text(stringResource(R.string.hall_title), style = BType.display(22), color = c.ink, modifier = Modifier.semantics { heading() })
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(stringResource(R.string.hall_earned, byKey.values.count { it.earned }), style = BType.label(11f, 0.14f), color = c.acc)
            val on = s?.public == true
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(16.dp)).background(c.s1).border(1.dp, c.line, RoundedCornerShape(16.dp))
                .toggleable(on, enabled = s != null, role = Role.Switch, onValueChange = onPublic).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(stringResource(R.string.hall_public), style = BType.body(15, FontWeight.Medium), color = c.ink)
                    Text(stringResource(if (on) R.string.hall_public_on else R.string.hall_public_off), style = BType.body(12), color = c.mut)
                }
                Switch(on, null, colors = SwitchDefaults.colors(checkedTrackColor = c.okFill, checkedThumbColor = c.s1, uncheckedTrackColor = c.track, uncheckedBorderColor = c.track, uncheckedThumbColor = c.s1))
            }
            DEFS.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { d -> BadgeTile(d, byKey[d.key], Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun BadgeTile(d: BadgeDef, b: BadgeDto?, modifier: Modifier) {
    val c = LocalBridgeColors.current
    val earned = b?.earned == true
    val name = stringResource(d.name)
    val sub = when {
        earned -> stringResource(R.string.badge_earned)
        b?.goal != null && d.key == "ref" -> stringResource(R.string.badge_entries_to_go, (b.goal - (b.progress ?: 0)).coerceAtLeast(0))
        b?.goal != null -> stringResource(R.string.badge_days_to_go, (b.goal - (b.progress ?: 0)).coerceAtLeast(0))
        else -> stringResource(d.hint)
    }
    val a11y = if (earned) stringResource(R.string.badge_a11y_earned, name) else stringResource(R.string.badge_a11y_locked, name, sub)
    Column(modifier.padding(bottom = 8.dp).semantics(mergeDescendants = true) { contentDescription = a11y }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(width = 76.dp, height = 86.dp).clip(Hex).background(if (earned) c.acc else c.track).padding(2.5.dp).clip(Hex).background(c.s1).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
            Text(d.glyph, style = BType.display(22), color = if (earned) c.accInk else c.mut2)
        }
        Text(name, style = BType.body(13, FontWeight.SemiBold, 1.25f), color = if (earned) c.ink else c.mut, textAlign = TextAlign.Center)
        Text(sub, style = BType.body(11, lineHeight = 1.35f), color = c.mut, textAlign = TextAlign.Center, modifier = Modifier.heightIn(min = 28.dp))
        if (!earned && b?.goal != null) Box(Modifier.width(60.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(c.track)) {
            Box(Modifier.fillMaxWidth(((b.progress ?: 0).toFloat() / b.goal).coerceIn(0f, 1f)).height(3.dp).background(c.mut))
        }
    }
}
