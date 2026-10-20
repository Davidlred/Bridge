package app.thebridge.feature.journal

import app.thebridge.ui.components.LiveTextField
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.thebridge.R
import app.thebridge.data.JournalItem
import app.thebridge.data.JournalRepository
import app.thebridge.data.local.JournalEntity
import app.thebridge.ui.components.BridgeSheet
import app.thebridge.ui.components.PrimaryButton
import app.thebridge.ui.components.SecondaryButton
import app.thebridge.ui.theme.BType
import app.thebridge.ui.theme.LocalBridgeColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

val MOODS = listOf(R.string.mood_drained, R.string.mood_low, R.string.mood_steady, R.string.mood_good, R.string.mood_strong)

data class JournalActions(
    val tab: (JournalTab) -> Unit = {}, val goal: (String) -> Unit = {}, val mood: (Int) -> Unit = {}, val text: (String) -> Unit = {},
    val lock: () -> Unit = {}, val back: (() -> Unit)? = null, val filter: (String?) -> Unit = {}, val open: (JournalItem) -> Unit = {},
    val keep: (JournalItem, Boolean) -> Unit = { _, _ -> },
)

@Composable
fun JournalScreen(ui: JournalUi, actions: JournalActions, dayIndex: (JournalEntity) -> Int?) {
    val c = LocalBridgeColors.current
    Column(Modifier.fillMaxSize().background(c.bg).imePadding().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        actions.back?.let { back ->
            Row(Modifier.offset(x = (-8).dp).heightIn(min = 44.dp).clickable(role = Role.Button, onClick = back).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(painterResource(R.drawable.ic_arrow_back), null, tint = c.ink2, modifier = Modifier.size(24.dp))
                Text(stringResource(R.string.tab_today), style = BType.body(14, FontWeight.Medium), color = c.ink2)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.journal_title), style = BType.display(25), color = c.ink, modifier = Modifier.semantics { heading() })
            Row(Modifier.background(c.s0, RoundedCornerShape(12.dp)).border(1.dp, c.line, RoundedCornerShape(12.dp)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                listOf(JournalTab.Today to R.string.seg_today, JournalTab.History to R.string.journal_history).forEach { (t, label) ->
                    val on = ui.tab == t
                    Box(Modifier.heightIn(min = 36.dp).background(if (on) c.acc else Color.Transparent, RoundedCornerShape(9.dp)).clickable(role = Role.Tab) { actions.tab(t) }.semantics { selected = on }.padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center) { Text(stringResource(label), style = BType.body(13, FontWeight.SemiBold), color = if (on) c.onAcc else c.mut) }
                }
            }
        }
        if (ui.goals.size > 1) GoalChips(ui, if (ui.tab == JournalTab.Today) ui.goalId else ui.filter, allowAll = ui.tab == JournalTab.History) { if (ui.tab == JournalTab.Today) it?.let(actions.goal) else actions.filter(it) }
        if (ui.tab == JournalTab.Today) TodayEntry(ui, actions) else History(ui, actions, dayIndex)
    }
}

@Composable
private fun GoalChips(ui: JournalUi, selected: String?, allowAll: Boolean, onPick: (String?) -> Unit) {
    val c = LocalBridgeColors.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val options = (if (allowAll) listOf<Pair<String?, String>>(null to stringResource(R.string.journal_all_goals)) else emptyList()) + ui.goals.map { it.goal.id to it.goal.label }
        options.forEach { (id, label) ->
            val on = selected == id
            Text(label, style = BType.body(13, FontWeight.SemiBold), color = if (on) c.ink else c.ink2, maxLines = 1,
                modifier = Modifier.heightIn(min = 40.dp).background(if (on) c.accTint else c.s1, RoundedCornerShape(20.dp)).border(1.dp, if (on) c.acc else c.line, RoundedCornerShape(20.dp))
                    .clickable(role = Role.RadioButton) { onPick(id) }.semantics { this.selected = on }.padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
}

@Composable
private fun TodayEntry(ui: JournalUi, actions: JournalActions) {
    val c = LocalBridgeColors.current
    val date = LocalDate.parse(ui.today)
    val day = ui.goal?.progress?.day
    Text((date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault())) + (day?.let { " · DAY $it" } ?: "")).uppercase(Locale.getDefault()), style = BType.label(11f, 0.14f), color = c.mut)
    ui.conflict?.let { ConflictBanner(it, actions.keep) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row { Text(stringResource(R.string.journal_feeling) + " ", style = BType.body(13, FontWeight.Medium), color = c.ink2); Text(stringResource(R.string.field_optional), style = BType.body(13, FontWeight.Medium), color = c.mut2) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { MOODS.forEachIndexed { i, label -> MoodButton(i, stringResource(label), ui.mood == i, Modifier.weight(1f)) { actions.mood(i) } } }
    }
    val hint = stringResource(R.string.journal_hint)
    LiveTextField(ui.text, actions.text, maxLength = JournalRepository.MAX_LENGTH, textStyle = BType.body(16, lineHeight = 1.65f).copy(color = c.ink), cursorBrush = SolidColor(c.acc),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp).semantics { contentDescription = hint },
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().heightIn(min = 300.dp).background(c.s1, RoundedCornerShape(20.dp)).border(1.dp, c.line, RoundedCornerShape(20.dp)).padding(20.dp)) {
                if (ui.text.isEmpty()) Text(hint, style = BType.body(16, lineHeight = 1.65f), color = c.mut2); inner()
            }
        })
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        val (icon, color, label) = when (ui.save) {
            SaveState.Saving -> Triple(R.drawable.ic_sync, c.mut, stringResource(R.string.journal_saving))
            SaveState.Saved -> Triple(R.drawable.ic_cloud_done, c.ok, stringResource(R.string.journal_saved))
            SaveState.SavedOffline -> Triple(R.drawable.ic_cloud_off, c.warn, stringResource(R.string.journal_saved_offline))
            SaveState.Idle -> Triple(R.drawable.ic_cloud, c.mut, stringResource(R.string.journal_autosave))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
            Icon(painterResource(icon), null, tint = color, modifier = Modifier.size(17.dp)); Text(label, style = BType.body(12), color = color)
        }
        Text(
            when {
                ui.text.length >= JournalRepository.COUNTER_FROM -> "${ui.text.length} / ${JournalRepository.MAX_LENGTH}"
                ui.words > 0 -> pluralStringResource(R.plurals.journal_words, ui.words, ui.words)
                else -> ""
            },
            style = BType.label(12f, 0f), color = if (ui.text.length >= JournalRepository.COUNTER_FROM) c.warn else c.mut,
        )
    }
    if (ui.canLock) PrimaryButton(stringResource(R.string.journal_lock_in), actions.lock)
}

/** Ring that fills from the bottom: 20% for Drained up to 100% for Strong. */
@Composable
private fun MoodButton(index: Int, label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalBridgeColors.current
    Column(modifier.heightIn(min = 70.dp).background(if (on) c.accTint else Color.Transparent, RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp))
        .clickable(role = Role.RadioButton, onClick = onClick).semantics { selected = on; contentDescription = label }.padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
        MoodRing(index, on, 24)
        Text(label, style = BType.body(11, FontWeight.Medium), color = if (on) c.ink else c.mut, maxLines = 1)
    }
}

@Composable
fun MoodRing(index: Int, on: Boolean, size: Int) {
    val c = LocalBridgeColors.current
    Box(Modifier.size(size.dp).clip(CircleShape).border(1.5.dp, if (on) c.acc else c.line, CircleShape), contentAlignment = Alignment.BottomCenter) {
        Box(Modifier.fillMaxWidth().fillMaxHeight((index + 1) * 0.2f).background(if (on) c.acc else c.mut2))
    }
}

@Composable
private fun ConflictBanner(item: JournalItem, keep: (JournalItem, Boolean) -> Unit) {
    val c = LocalBridgeColors.current
    val other = item.conflict ?: return
    Column(Modifier.fillMaxWidth().background(c.warnTint, RoundedCornerShape(16.dp)).border(1.dp, c.warn, RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.journal_conflict_title), style = BType.display(16), color = c.ink)
        Text(stringResource(R.string.journal_conflict_body), style = BType.body(13, lineHeight = 1.45f), color = c.ink2)
        Text(other.body.ifBlank { "—" }, style = BType.body(14, lineHeight = 1.5f), color = c.ink, maxLines = 6, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().background(c.s1, RoundedCornerShape(12.dp)).padding(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(stringResource(R.string.journal_keep_this), { keep(item, false) }, Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.journal_keep_other), { keep(item, true) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun History(ui: JournalUi, actions: JournalActions, dayIndex: (JournalEntity) -> Int?) {
    val c = LocalBridgeColors.current
    val items = ui.shownHistory.filter { it.written }
    if (items.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 60.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(width = 84.dp, height = 46.dp)) {
                Box(Modifier.size(84.dp, 42.dp).clip(RoundedCornerShape(topStart = 42.dp, topEnd = 42.dp)).border(2.dp, c.accLine, RoundedCornerShape(topStart = 42.dp, topEnd = 42.dp)))
                Box(Modifier.align(Alignment.BottomStart).offset(x = 4.dp).size(4.dp, 26.dp).background(c.acc))
                Box(Modifier.align(Alignment.BottomEnd).offset(x = (-4).dp).size(4.dp, 26.dp).background(c.track))
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(c.track))
            }
            Text(stringResource(R.string.journal_empty_history), style = BType.display(20, 1.25f), color = c.ink, textAlign = TextAlign.Center)
            Box(Modifier.heightIn(min = 48.dp).background(c.acc, RoundedCornerShape(14.dp)).clickable(role = Role.Button) { actions.tab(JournalTab.Today) }.padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.journal_write_today), style = BType.body(14, FontWeight.SemiBold), color = c.onAcc)
            }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { item ->
            val e = item.entry
            val date = LocalDate.parse(e.localDate)
            Column(Modifier.fillMaxWidth().background(c.s1, RoundedCornerShape(18.dp)).border(1.dp, c.line, RoundedCornerShape(18.dp)).clickable(role = Role.Button) { actions.open(item) }.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (e.localDate == ui.today) stringResource(R.string.seg_today) else date.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())), style = BType.display(15), color = c.ink)
                    dayIndex(e)?.let { Text(stringResource(R.string.journal_day, it), style = BType.label(10f, 0.12f), color = c.mut) }
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    e.mood?.let { m -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { MoodRing(m, false, 14); Text(stringResource(MOODS[m]), style = BType.body(12), color = c.mut) } }
                }
                if (e.body.isNotBlank()) Text(e.body, style = BType.body(14, lineHeight = 1.5f), color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Read a past entry, or edit it. */
@Composable
fun EntrySheet(item: JournalItem, dayIndex: Int?, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val c = LocalBridgeColors.current
    val e = item.entry
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf(e.body) }
    BridgeSheet(onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(LocalDate.parse(e.localDate).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)), style = BType.display(20), color = c.ink, modifier = Modifier.semantics { heading() })
                dayIndex?.let { Text(stringResource(R.string.journal_day, it), style = BType.label(10f, 0.12f), color = c.mut) }
            }
            Text(e.mood?.let { stringResource(MOODS[it]) } ?: stringResource(R.string.journal_no_mood), style = BType.body(12, FontWeight.Medium), color = c.ink2,
                modifier = Modifier.background(c.track, RoundedCornerShape(13.dp)).padding(horizontal = 10.dp, vertical = 5.dp))
            if (!editing) {
                Text(e.body, style = BType.body(16, lineHeight = 1.65f), color = c.ink)
                SecondaryButton(stringResource(R.string.journal_edit_entry), { editing = true })
            } else {
                BasicTextField(draft, { draft = it.take(JournalRepository.MAX_LENGTH) }, textStyle = BType.body(16, lineHeight = 1.6f).copy(color = c.ink), cursorBrush = SolidColor(c.acc),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp).background(c.bg, RoundedCornerShape(14.dp)).border(1.dp, c.acc, RoundedCornerShape(14.dp)).padding(14.dp))
                PrimaryButton(stringResource(R.string.journal_save_entry), { onSave(draft) })
            }
        }
    }
}
