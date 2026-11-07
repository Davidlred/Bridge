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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.thebridge.R
import app.thebridge.data.SocialRepository
import app.thebridge.data.remote.ApiException
import app.thebridge.data.remote.ClassTaskDto
import app.thebridge.data.remote.OfflineException
import app.thebridge.data.remote.StudentDetailDto
import app.thebridge.data.remote.StudentDto
import app.thebridge.data.remote.StudentsDto
import app.thebridge.ui.Toaster
import app.thebridge.ui.components.PrimaryButton
import app.thebridge.ui.components.ToastKind
import app.thebridge.ui.components.UiText
import app.thebridge.ui.theme.BType
import app.thebridge.ui.theme.LocalBridgeColors
import app.thebridge.ui.theme.Space
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt

/** Loading, a result, or why there is none. Students only load online (the lead's view of other people isn't cached). */
data class Loadable<T>(val data: T? = null, val loading: Boolean = true, val offline: Boolean = false, val error: Boolean = false)

// --- The class: everyone on the plan, those who need attention first, and each task across the class ---

@HiltViewModel
class StudentsViewModel @Inject constructor(saved: SavedStateHandle, private val social: SocialRepository) : ViewModel() {
    val route = saved.toRoute<app.thebridge.nav.Students>()
    private val _state = MutableStateFlow(Loadable<StudentsDto>())
    val state: StateFlow<Loadable<StudentsDto>> = _state.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = false, offline = false) }
        try { _state.value = Loadable(social.students(route.allianceId), loading = false) }
        catch (e: Exception) { _state.update { it.copy(loading = false, offline = e is OfflineException, error = e !is OfflineException) } }
    }
}

data class StudentsActions(val back: () -> Unit = {}, val retry: () -> Unit = {}, val open: (StudentDto) -> Unit = {})

@Composable
fun StudentsRoute(onBack: () -> Unit, onOpen: (app.thebridge.nav.Student) -> Unit, vm: StudentsViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    // Back from a student: their nudge or new ticks show up here.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { if (ui.data != null) vm.load(); onPauseOrDispose { } }
    StudentsScreen(ui, vm.route.code, StudentsActions(back = onBack, retry = { vm.load() }, open = { onOpen(app.thebridge.nav.Student(vm.route.allianceId, it.userId, it.name, ui.data?.name.orEmpty())) }))
}

@Composable
fun StudentsScreen(ui: Loadable<StudentsDto>, code: String, actions: StudentsActions, today: LocalDate = LocalDate.now()) {
    val c = LocalBridgeColors.current
    val d = ui.data
    Column(Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState()).padding(start = Space.screen, end = Space.screen, top = 6.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BackRow(d?.name.orEmpty(), actions.back)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.students_title), style = BType.display(25), color = c.ink, modifier = Modifier.semantics { heading() })
            if (d != null) Text(stringResource(R.string.students_sub, d.sharing, d.members), style = BType.body(13), color = c.mut)
        }
        when {
            d == null && ui.loading -> repeat(4) { Box(Modifier.fillMaxWidth().height(72.dp).background(c.s1, RoundedCornerShape(16.dp)).clearAndSetSemantics { }) }
            d == null -> Problem(stringResource(if (ui.offline) R.string.students_offline else R.string.students_error), actions.retry)
            d.students.isEmpty() -> Column(Modifier.fillMaxWidth().border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(vertical = 28.dp, horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.students_empty_title), style = BType.display(16), color = c.ink)
                Text(stringResource(R.string.students_empty_body, code), style = BType.body(13, lineHeight = 1.45f), color = c.mut, textAlign = TextAlign.Center)
            }
            else -> {
                ClassWeek(d)
                val (attention, rest) = d.students.partition { it.progress?.attention != null }
                if (attention.isNotEmpty()) Section(stringResource(R.string.students_attention)) { attention.forEach { StudentRow(it, today, actions.open) } }
                if (rest.isNotEmpty()) Section(stringResource(R.string.students_everyone)) { rest.forEach { StudentRow(it, today, actions.open) } }
                if (d.tasks.isNotEmpty()) Section(stringResource(R.string.students_by_task)) { d.tasks.forEach { TaskRate(it) } }
            }
        }
    }
}

@Composable
private fun ClassWeek(d: StudentsDto) {
    val c = LocalBridgeColors.current
    val pct = (d.week.rate * 100).roundToInt()
    Column(Modifier.fillMaxWidth().background(c.s1, RoundedCornerShape(20.dp)).border(1.dp, c.line, RoundedCornerShape(20.dp)).padding(18.dp).semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.students_class_week), style = BType.label(10f, 0.12f), color = c.mut)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.students_pct, pct), style = BType.display(32, 1f), color = c.ink)
            Text(stringResource(R.string.students_class_done, d.week.done, d.week.due), style = BType.body(13), color = c.mut, modifier = Modifier.padding(bottom = 4.dp))
        }
        Meter(d.week.rate, c.acc, 6.dp)
    }
}

@Composable
private fun StudentRow(s: StudentDto, today: LocalDate, onOpen: (StudentDto) -> Unit) {
    val c = LocalBridgeColors.current
    val p = s.progress
    val status = statusLine(s, today)
    val pct = p?.let { (it.week.rate * 100).roundToInt() }
    val a11y = if (pct != null) stringResource(R.string.students_row_a11y, s.name, status, stringResource(R.string.students_pct, pct)) else "${s.name}, $status"
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp)).background(c.s1).border(1.dp, c.line, RoundedCornerShape(16.dp))
        .then(if (p != null) Modifier.clickable(role = Role.Button) { onOpen(s) } else Modifier)
        .padding(horizontal = 14.dp, vertical = 10.dp).clearAndSetSemantics { contentDescription = a11y },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Avatar(s.name, 38.dp, c.ink2)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(s.name, style = BType.body(15, FontWeight.SemiBold), color = c.ink, maxLines = 1)
            Text(status, style = BType.body(12, lineHeight = 1.35f), color = if (p?.attention != null) c.bad else c.mut)
        }
        if (pct != null) Column(Modifier.width(64.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(stringResource(R.string.students_pct, pct), style = BType.label(13f, 0f), color = c.ink)
            Meter(p.week.rate, c.ink2, 4.dp)
        }
        if (p != null) Icon(painterResource(R.drawable.ic_chevron_right), null, tint = c.mut2, modifier = Modifier.size(20.dp))
    }
}

/** Why someone is in "Needs attention", or how today is going. */
@Composable
private fun statusLine(s: StudentDto, today: LocalDate): String {
    val p = s.progress ?: return stringResource(R.string.students_not_sharing)
    if (s.finished) return stringResource(R.string.students_finished)
    return when (p.attention) {
        "away" -> p.lastActive?.let { pluralStringResource(R.plurals.students_away, days(it, today), days(it, today)) } ?: stringResource(R.string.students_away_never)
        "low_feel" -> stringResource(R.string.students_low_feel)
        "behind" -> stringResource(R.string.students_behind)
        else -> stringResource(R.string.students_today, p.today.done, p.today.total)
    }
}

private fun days(date: String, today: LocalDate) = runCatching { ChronoUnit.DAYS.between(LocalDate.parse(date), today).toInt() }.getOrDefault(0).coerceAtLeast(1)

@Composable
private fun TaskRate(t: ClassTaskDto) {
    val c = LocalBridgeColors.current
    val rate = if (t.due == 0) 0f else t.done.toFloat() / t.due
    Column(Modifier.fillMaxWidth().background(c.s1, RoundedCornerShape(16.dp)).border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(14.dp).semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(t.title, style = BType.body(14, FontWeight.Medium, 1.35f), color = c.ink, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.students_task_done, (rate * 100).roundToInt()), style = BType.label(12f, 0f), color = c.ink2)
        }
        Meter(rate, c.ink2, 4.dp)
        t.feel?.let { Text(stringResource(R.string.students_task_feel, feelWord(it.roundToInt()).lowercase(Locale.getDefault())), style = BType.body(12), color = if (it <= 2) c.bad else c.mut) }
    }
}

// --- One student: their last 7 days task by task, check-ins, and a nudge ---

@HiltViewModel
class StudentViewModel @Inject constructor(saved: SavedStateHandle, private val social: SocialRepository, private val toaster: Toaster) : ViewModel() {
    val route = saved.toRoute<app.thebridge.nav.Student>()
    private val _state = MutableStateFlow(Loadable<StudentDetailDto>())
    val state: StateFlow<Loadable<StudentDetailDto>> = _state.asStateFlow()
    /** They turned sharing off since the list loaded. */
    val stopped = MutableStateFlow(false)
    val nudging = MutableStateFlow(false)

    init { load() }

    fun load() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = false, offline = false) }
        try { _state.value = Loadable(social.student(route.allianceId, route.userId), loading = false) }
        catch (e: Exception) {
            if ((e as? ApiException)?.code == "not_sharing") stopped.value = true
            _state.update { it.copy(loading = false, offline = e is OfflineException, error = e !is OfflineException) }
        }
    }

    fun nudge() = viewModelScope.launch {
        val s = _state.value.data ?: return@launch
        if (nudging.value) return@launch
        nudging.value = true
        try {
            social.nudgeStudent(route.allianceId, route.userId)
            _state.update { it.copy(data = s.copy(nudgedToday = true)) }
            toaster.show(UiText.Res(R.string.toast_student_nudged, listOf(s.name)), ToastKind.Ok)
        } catch (e: Exception) {
            if ((e as? ApiException)?.code == "already_nudged") _state.update { it.copy(data = s.copy(nudgedToday = true)) }
            else toaster.show(UiText.Res(if (e is OfflineException) R.string.error_offline else R.string.error_generic), ToastKind.Warn)
        } finally { nudging.value = false }
    }
}

data class StudentActions(val back: () -> Unit = {}, val retry: () -> Unit = {}, val nudge: () -> Unit = {})

@Composable
fun StudentRoute(onBack: () -> Unit, vm: StudentViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val stopped by vm.stopped.collectAsStateWithLifecycle()
    val nudging by vm.nudging.collectAsStateWithLifecycle()
    StudentScreen(ui, vm.route.name, vm.route.plan, stopped, nudging, StudentActions(back = onBack, retry = { vm.load() }, nudge = { vm.nudge() }))
}

@Composable
fun StudentScreen(ui: Loadable<StudentDetailDto>, name: String, plan: String, stopped: Boolean, nudging: Boolean, actions: StudentActions, today: LocalDate = LocalDate.now()) {
    val c = LocalBridgeColors.current
    val d = ui.data
    Column(Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState()).padding(start = Space.screen, end = Space.screen, top = 6.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BackRow(stringResource(R.string.students_title), actions.back)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Avatar(name, 52.dp, c.accInk)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(name, style = BType.display(25), color = c.ink, modifier = Modifier.semantics { heading() })
                d?.startDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let {
                    Text(stringResource(R.string.student_joined, it.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))), style = BType.body(13), color = c.mut)
                }
            }
        }
        when {
            stopped -> Problem(stringResource(R.string.student_not_sharing, name), null)
            d == null && ui.loading -> repeat(3) { Box(Modifier.fillMaxWidth().height(96.dp).background(c.s1, RoundedCornerShape(16.dp)).clearAndSetSemantics { }) }
            d == null -> Problem(stringResource(if (ui.offline) R.string.students_offline else R.string.students_error), actions.retry)
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile(stringResource(R.string.student_week), stringResource(R.string.students_pct, (d.week.rate * 100).roundToInt()), Modifier.weight(1f))
                    Tile(stringResource(R.string.student_streak), pluralStringResource(R.plurals.student_streak_days, d.streak, d.streak), Modifier.weight(1f))
                    Tile(stringResource(R.string.student_feel), d.feel?.let { feelWord(it.roundToInt()) } ?: "–", Modifier.weight(1f))
                }
                d.attention?.let { a ->
                    val why = when (a) {
                        "away" -> d.lastActive?.let { pluralStringResource(R.plurals.students_away, days(it, today), days(it, today)) } ?: stringResource(R.string.students_away_never)
                        "low_feel" -> stringResource(R.string.students_low_feel)
                        else -> stringResource(R.string.students_behind)
                    }
                    Text(why, style = BType.body(13, FontWeight.Medium), color = c.bad, modifier = Modifier.fillMaxWidth().background(c.badTint, RoundedCornerShape(14.dp)).padding(12.dp))
                }
                Section(stringResource(R.string.student_week_grid)) { WeekGrid(d) }
                Section(stringResource(R.string.student_checkins)) {
                    val notes = d.tasks.flatMap { t -> t.days.filter { it.feel != null || it.amount != null || it.note != null }.map { t to it } }.sortedByDescending { it.second.date }
                    if (notes.isEmpty()) Text(stringResource(R.string.student_checkins_empty), style = BType.body(13), color = c.mut)
                    notes.forEach { (t, day) -> CheckinCard(t.title, t.measure, day) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(stringResource(if (d.nudgedToday) R.string.student_nudged else R.string.student_nudge), actions.nudge, enabled = !d.nudgedToday && !d.finished, loading = nudging)
                    Text(stringResource(R.string.student_nudge_sub, plan), style = BType.body(12, lineHeight = 1.4f), color = c.mut)
                }
            }
        }
    }
}

/** One row per task, one dot per day: done, missed, not yet (today) or before they joined. */
@Composable
private fun WeekGrid(d: StudentDetailDto) {
    val c = LocalBridgeColors.current
    val dates = d.days.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
    val dayName = { x: LocalDate -> x.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
    val labels = mapOf("done" to stringResource(R.string.student_day_done, "%s"), "missed" to stringResource(R.string.student_day_missed, "%s"),
        "open" to stringResource(R.string.student_day_open, "%s"), "before" to stringResource(R.string.student_day_before, "%s"))
    Column(Modifier.fillMaxWidth().background(c.s1, RoundedCornerShape(18.dp)).border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f))
            dates.forEach { Text(dayName(it).take(1), style = BType.label(10f, 0f), color = c.mut, textAlign = TextAlign.Center, modifier = Modifier.width(26.dp)) }
        }
        d.tasks.forEach { t ->
            val said = t.days.zip(dates).joinToString(", ") { (x, date) -> (labels[x.state] ?: labels.getValue("before")).format(dayName(date)) }
            Row(Modifier.clearAndSetSemantics { contentDescription = "${t.title}. $said" }, verticalAlignment = Alignment.CenterVertically) {
                Text(t.title, style = BType.body(13, FontWeight.Medium, 1.3f), color = c.ink, modifier = Modifier.weight(1f).padding(end = 8.dp), maxLines = 2)
                t.days.forEach { Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) { Dot(it.state) } }
            }
        }
    }
}

@Composable
private fun Dot(state: String) {
    val c = LocalBridgeColors.current
    when (state) {
        "done" -> Box(Modifier.size(18.dp).background(c.okFill, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_check), null, tint = c.onOk, modifier = Modifier.size(12.dp))
        }
        "missed" -> Box(Modifier.size(18.dp).border(1.5.dp, c.bad, CircleShape))
        "open" -> Box(Modifier.size(18.dp).dashed(c.line2, 9.dp))
        else -> Box(Modifier.size(5.dp).background(c.line2, CircleShape))
    }
}

@Composable
private fun CheckinCard(title: String, measure: String?, d: app.thebridge.data.remote.StudentDayDto) {
    val c = LocalBridgeColors.current
    val date = runCatching { LocalDate.parse(d.date) }.getOrNull()
    val meta = listOfNotNull(date?.dayOfWeek?.getDisplayName(TextStyle.SHORT, Locale.getDefault()), d.feel?.let { feelWord(it) }, d.amount?.let { amountText(it, measure) }).joinToString(" · ")
    Column(Modifier.fillMaxWidth().background(c.s1, RoundedCornerShape(16.dp)).border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(14.dp).semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = BType.body(14, FontWeight.SemiBold), color = c.ink)
        Text(meta, style = BType.body(12), color = if ((d.feel ?: 3) <= 2) c.bad else c.mut)
        d.note?.let { Text(it, style = BType.body(14, lineHeight = 1.45f), color = c.ink2) }
    }
}

// --- Shared bits ---

@Composable
private fun BackRow(label: String, onBack: () -> Unit) {
    val c = LocalBridgeColors.current
    Row(Modifier.offset(x = (-8).dp).heightIn(min = 44.dp).clickable(role = Role.Button, onClickLabel = stringResource(R.string.back), onClick = onBack).padding(end = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_chevron_left), null, tint = c.ink, modifier = Modifier.size(24.dp))
        Text(label, style = BType.body(15, FontWeight.Medium), color = c.ink, maxLines = 1)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val c = LocalBridgeColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(Locale.getDefault()), style = BType.label(11f, 0.14f), color = c.mut, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier) {
    val c = LocalBridgeColors.current
    Column(modifier.background(c.s1, RoundedCornerShape(16.dp)).border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(12.dp).semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = BType.label(9.5f, 0.12f), color = c.mut, maxLines = 1)
        Text(value, style = BType.display(18, 1.1f), color = c.ink)
    }
}

@Composable
private fun Meter(fraction: Float, color: Color, height: Dp) {
    val c = LocalBridgeColors.current
    Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(c.track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).background(color))
    }
}

@Composable
private fun Problem(text: String, onRetry: (() -> Unit)?) {
    val c = LocalBridgeColors.current
    Column(Modifier.fillMaxWidth().background(c.s1, RoundedCornerShape(18.dp)).border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, style = BType.body(14, FontWeight.Medium, 1.45f), color = c.ink)
        if (onRetry != null) Text(stringResource(R.string.retry), style = BType.body(14, FontWeight.SemiBold), color = c.ink,
            modifier = Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onRetry).padding(horizontal = 18.dp, vertical = 12.dp))
    }
}

/** "Very hard" … "Great". */
@Composable
fun feelWord(feel: Int): String = stringResource(when (feel.coerceIn(1, 5)) { 1 -> R.string.feel_1; 2 -> R.string.feel_2; 3 -> R.string.feel_3; 4 -> R.string.feel_4; else -> R.string.feel_5 })

/** "12 reps", "42.5 kg". */
@Composable
fun amountText(amount: Double, measure: String?): String {
    val n = if (amount % 1.0 == 0.0) amount.toLong().toString() else amount.toString()
    return when (measure) {
        "reps" -> stringResource(R.string.unit_reps, n); "kg" -> stringResource(R.string.unit_kg, n); "min" -> stringResource(R.string.unit_min, n)
        "km" -> stringResource(R.string.unit_km, n); "pages" -> stringResource(R.string.unit_pages, n); else -> n
    }
}
