package app.thebridge.feature.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.thebridge.R
import app.thebridge.data.PlanTask
import app.thebridge.data.local.CheckinEntity
import app.thebridge.feature.community.feelWord
import app.thebridge.ui.components.BigTextArea
import app.thebridge.ui.components.BridgeField
import app.thebridge.ui.components.BridgeSheet
import app.thebridge.ui.components.PrimaryButton
import app.thebridge.ui.components.SecondaryButton
import app.thebridge.ui.components.SheetTitle
import app.thebridge.ui.theme.BType
import app.thebridge.ui.theme.LocalBridgeColors

/** Who leads a shared-plan goal, and whether they see this user's check-ins. */
data class LeadInfo(val name: String, val sees: Boolean, val isMe: Boolean)

val MEASURES = listOf("reps", "kg", "min", "km", "pages")

@Composable
fun measureLabel(m: String?): String = stringResource(when (m) {
    "reps" -> R.string.measure_reps; "kg" -> R.string.measure_kg; "min" -> R.string.measure_min; "km" -> R.string.measure_km; "pages" -> R.string.measure_pages
    else -> R.string.measure_none
})

/** After ticking: how it felt, how many (in the task's unit) and a note. Everything is optional; Skip saves nothing. */
@Composable
fun CheckInSheet(t: PlanTask, existing: CheckinEntity?, lead: LeadInfo?, onSave: (Int?, Double?, String?) -> Unit, onSkip: () -> Unit) {
    BridgeSheet(onSkip) { CheckInContent(t, existing, lead, onSave, onSkip) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CheckInContent(t: PlanTask, existing: CheckinEntity?, lead: LeadInfo?, onSave: (Int?, Double?, String?) -> Unit, onSkip: () -> Unit) {
    val c = LocalBridgeColors.current
    var feel by rememberSaveable { mutableStateOf(existing?.feel) }
    var amount by rememberSaveable { mutableStateOf(existing?.amount?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(existing?.note.orEmpty()) }
    val measure = t.task.measure
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SheetTitle(stringResource(R.string.checkin_title))
            Text(t.task.title, style = BType.body(14), color = c.mut)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.checkin_feel), style = BType.label(11f, 0.12f), color = c.mut)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..5).forEach { n -> Option(feelWord(n), feel == n) { feel = if (feel == n) null else n } }
            }
        }
        if (measure != null) BridgeField(measureLabel(measure), amount, { v -> amount = v.filter { it.isDigit() || it == '.' || it == ',' }.replace(',', '.').take(7) },
            stringResource(R.string.checkin_amount_hint), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(stringResource(R.string.checkin_note), style = BType.label(11f, 0.12f), color = c.mut)
            BigTextArea(note, { note = it.take(200) }, stringResource(R.string.checkin_note_hint), stringResource(R.string.checkin_note), minHeight = 76, maxLength = 200)
        }
        // Who sees it, said plainly before saving.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(if (lead != null && lead.sees && !lead.isMe) R.drawable.ic_visibility else R.drawable.ic_lock), null, tint = c.mut, modifier = Modifier.size(16.dp))
            Text(when {
                lead == null || lead.isMe -> stringResource(R.string.checkin_private)
                lead.sees -> stringResource(R.string.checkin_seen_by, lead.name)
                else -> stringResource(R.string.checkin_private_shared, lead.name)
            }, style = BType.body(12, lineHeight = 1.4f), color = c.mut)
        }
        PrimaryButton(stringResource(R.string.checkin_save), { onSave(feel, amount.toDoubleOrNull()?.coerceIn(0.0, 99_999.0), note.trim().ifEmpty { null }) })
        SecondaryButton(stringResource(R.string.checkin_skip), onSkip)
    }
}

/** Unit asked for after ticking, picked on the task. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MeasurePicker(value: String?, onChange: (String?) -> Unit) {
    val c = LocalBridgeColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.task_measure), style = BType.label(11f, 0.12f), color = c.mut)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (listOf<String?>(null) + MEASURES).forEach { m -> Option(measureLabel(m), value == m) { onChange(m) } }
        }
        Text(stringResource(R.string.task_measure_sub), style = BType.body(12, lineHeight = 1.4f), color = c.mut)
    }
}

/** A choice chip: 36dp inside a 44dp touch target. */
@Composable
private fun Option(label: String, on: Boolean, onClick: () -> Unit) {
    val c = LocalBridgeColors.current
    val shape = RoundedCornerShape(18.dp)
    Box(Modifier.heightIn(min = 44.dp).clip(shape).clickable(role = Role.RadioButton, onClick = onClick).semantics { selected = on }, contentAlignment = Alignment.Center) {
        Text(label, style = BType.body(13, FontWeight.Medium), color = if (on) c.ink else c.ink2, maxLines = 1,
            modifier = Modifier.heightIn(min = 36.dp).background(if (on) (if (c.isDark) c.s2 else c.s1) else Color.Transparent, shape)
                .border(if (on) 1.5.dp else 1.dp, if (on) c.ink2 else c.line, shape).padding(horizontal = 14.dp, vertical = 9.dp))
    }
}

/** Shown in the task's details once it has a check-in today. */
@Composable
fun CheckinSummary(ci: CheckinEntity, measure: String?) {
    val c = LocalBridgeColors.current
    val parts = listOfNotNull(ci.feel?.let { feelWord(it) }, ci.amount?.let { app.thebridge.feature.community.amountText(it, measure) })
    Column(Modifier.background(c.s0, RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(12.dp).semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = BType.body(13, FontWeight.Medium), color = c.ink)
        ci.note?.let { Text(it, style = BType.body(13, lineHeight = 1.45f), color = c.ink2) }
    }
}
