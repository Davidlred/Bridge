package app.thebridge.ui.components

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebridge.R
import app.thebridge.domain.Deadlines
import app.thebridge.ui.theme.BType
import app.thebridge.ui.theme.LocalBridgeColors
import app.thebridge.ui.theme.Radius
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Back arrow and four progress bars (read out as "Step N of 4"). [onBack] null hides the arrow but keeps the space. */
@Composable
fun StepHeader(step: Int, onBack: (() -> Unit)?) {
    val c = LocalBridgeColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (onBack != null) BackButton(onBack, Modifier.offset(x = (-10).dp)) else Box(Modifier.size(width = 34.dp, height = 44.dp))
        val stepLabel = stringResource(R.string.ob_step, step)
        Row(Modifier.weight(1f).padding(end = 10.dp).semantics { contentDescription = stepLabel }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(4) { i -> Box(Modifier.weight(1f).height(3.dp).background(if (i < step) c.acc else c.track, RoundedCornerShape(2.dp))) }
        }
    }
}

/** Bricolage 30 step title. */
@Composable
fun StepTitle(title: String) {
    val c = LocalBridgeColors.current
    Text(title, style = BType.display(30, 1.12f), color = c.ink, modifier = Modifier.semantics { heading() })
}

/** Multi-line field on the surface colour, radius 18. */
@Composable
fun BigTextArea(value: String, onChange: (String) -> Unit, placeholder: String, a11y: String, modifier: Modifier = Modifier, large: Boolean = false, minHeight: Int = 118, maxLength: Int = Int.MAX_VALUE) {
    val c = LocalBridgeColors.current
    var focused by remember { mutableStateOf(false) }
    val style = if (large) BType.body(20, FontWeight.Medium, 1.4f) else BType.body(16, lineHeight = 1.55f)
    LiveTextField(
        value = value, onValueChange = onChange, textStyle = style.copy(color = c.ink), cursorBrush = SolidColor(c.ink), maxLength = maxLength,
        keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences),
        modifier = modifier.fillMaxWidth().heightIn(min = minHeight.dp).semantics { contentDescription = a11y }
            .onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().heightIn(min = minHeight.dp).background(c.s1, RoundedCornerShape(18.dp)).border(if (focused) 1.5.dp else 1.dp, if (focused) c.mut else c.line, RoundedCornerShape(18.dp)).padding(18.dp)) {
                if (value.isEmpty()) Text(placeholder, style = style, color = c.mut2)
                inner()
            }
        },
    )
}

/** Warning callout (amber), as used for the goal hints and the review "too many tasks" note. */
@Composable
fun WarnNote(text: String, icon: Int, modifier: Modifier = Modifier) {
    val c = LocalBridgeColors.current
    Row(modifier.fillMaxWidth().background(c.warnTint, RoundedCornerShape(14.dp)).border(1.dp, c.warn, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(painterResource(icon), null, tint = c.warn, modifier = Modifier.size(18.dp))
        Text(text, style = BType.body(13, lineHeight = 1.45f), color = c.warn)
    }
}

fun shortDate(d: LocalDate): String = d.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

/** Read-only field that opens the system date picker. Dates before [minDays] from today and beyond 5 years can't be picked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String?, value: LocalDate?, today: LocalDate, minDays: Int, onPick: (LocalDate) -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val c = LocalBridgeColors.current
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        if (label != null) Text(label, style = BType.label(if (compact) 10f else 11f, 0.12f), color = c.mut)
        Box(
            Modifier.fillMaxWidth().heightIn(min = if (compact) 48.dp else 52.dp).background(c.s1, RoundedCornerShape(if (compact) 12.dp else Radius.input))
                .border(1.dp, c.line, RoundedCornerShape(if (compact) 12.dp else Radius.input)).clip(RoundedCornerShape(if (compact) 12.dp else Radius.input)).clickable(role = Role.Button) { open = true }.padding(horizontal = if (compact) 12.dp else 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(value?.let(::shortDate) ?: stringResource(R.string.target_date_pick), style = BType.body(if (compact) 14 else 16, FontWeight.Medium), color = if (value == null) c.mut2 else c.ink)
        }
    }
    if (open) {
        val min = today.plusDays(minDays.toLong()); val max = today.plusDays(Deadlines.MAX_DAYS.toLong())
        val toMs = { d: LocalDate -> d.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }
        val state = rememberDatePickerState(initialSelectedDateMillis = (value ?: min).let(toMs), yearRange = today.year..max.year,
            selectableDates = object : SelectableDates { override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis in toMs(min)..toMs(max) })
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }; open = false }) { Text(stringResource(android.R.string.ok)) } },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
            colors = DatePickerDefaults.colors(containerColor = c.s1),
        ) { DatePicker(state, colors = DatePickerDefaults.colors(containerColor = c.s1, selectedDayContainerColor = c.acc, selectedDayContentColor = c.onAcc, todayDateBorderColor = c.acc)) }
    }
}

/** A generated goal image. Cached on disk by a stable key, because the signed link changes every hour. */
@Composable
fun GoalImage(url: String?, description: String?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    if (url == null) { Box(modifier.background(LocalBridgeColors.current.s0)); return }
    val key = url.substringBefore('?') + "#" + Regex("[?&]v=([^&]+)").find(url)?.groupValues?.get(1).orEmpty()
    AsyncImage(
        model = ImageRequest.Builder(ctx).data(url).diskCacheKey(key).memoryCacheKey(key).crossfade(true).build(),
        contentDescription = description, contentScale = ContentScale.Crop, modifier = modifier.background(LocalBridgeColors.current.s0),
    )
}

/** Priority pill: 22h, radius 11, 6dp dot, Geist 600 11. */
@Composable
fun PriorityPill(priority: String) {
    val c = LocalBridgeColors.current
    // Neutral pill; the dot carries the priority colour.
    val (fg, _, label) = when (priority) {
        "High" -> Triple(c.pHi, c.pHiTint, R.string.priority_high)
        "Low" -> Triple(c.pLo, c.pLoTint, R.string.priority_low)
        else -> Triple(c.pMd, c.pMdTint, R.string.priority_medium)
    }
    val text = stringResource(label)
    val a11y = stringResource(R.string.priority_a11y, text)
    Row(Modifier.heightIn(min = 22.dp).background(c.accTint, RoundedCornerShape(Radius.pill)).padding(horizontal = 9.dp).semantics(mergeDescendants = true) { contentDescription = a11y },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(6.dp).background(fg, CircleShape))
        Text(text, style = BType.body(11, FontWeight.SemiBold), color = c.ink2)
    }
}

/** Three-way segmented control used for task priority. */
@Composable
fun PrioritySelector(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalBridgeColors.current
    Row(modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(12.dp)).border(1.dp, c.line, RoundedCornerShape(12.dp)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        listOf("High" to R.string.priority_high, "Medium" to R.string.priority_medium, "Low" to R.string.priority_low).forEach { (key, label) ->
            val on = key == value
            Box(Modifier.weight(1f).heightIn(min = 40.dp).background(if (on) c.accTint else Color.Transparent, RoundedCornerShape(9.dp)).border(1.dp, if (on) c.line2 else Color.Transparent, RoundedCornerShape(9.dp))
                .clip(RoundedCornerShape(9.dp)).clickable(role = Role.RadioButton) { onChange(key) }, contentAlignment = Alignment.Center) {
                Text(stringResource(label), style = BType.body(12, FontWeight.SemiBold), color = if (on) c.ink else c.mut)
            }
        }
    }
}
