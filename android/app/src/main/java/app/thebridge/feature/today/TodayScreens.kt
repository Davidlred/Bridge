package app.thebridge.feature.today

import androidx.compose.foundation.selection.toggleable
import app.thebridge.data.local.GoalEntity
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.thebridge.R
import app.thebridge.data.GoalState
import app.thebridge.data.PlanTask
import app.thebridge.domain.Deadlines
import app.thebridge.feature.onboarding.DeadlineMessage
import app.thebridge.feature.onboarding.FeasibilityCard
import app.thebridge.ui.components.BigTextArea
import app.thebridge.ui.components.BridgeField
import app.thebridge.ui.components.BridgeSheet
import app.thebridge.ui.components.DateField
import app.thebridge.ui.components.GoalImage
import app.thebridge.ui.components.PrimaryButton
import app.thebridge.ui.components.PriorityPill
import app.thebridge.ui.components.PrioritySelector
import app.thebridge.ui.components.SecondaryButton
import app.thebridge.ui.components.SheetTitle
import app.thebridge.ui.components.shortDate
import app.thebridge.ui.theme.BType
import app.thebridge.ui.theme.LocalBridgeColors
import app.thebridge.ui.theme.LocalReduceMotion
import app.thebridge.ui.theme.Motion
import app.thebridge.ui.theme.Space
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Callbacks from the Today tab, so the screen stays stateless and snapshot-testable. */
data class TodayActions(
    val openGoal: (String?) -> Unit = {}, val showView: (GoalView) -> Unit = {}, val toggle: (String, Boolean) -> Unit = { _, _ -> },
    val addGoal: () -> Unit = {}, val openTask: (PlanTask) -> Unit = {}, val newTask: () -> Unit = {}, val editGoal: (GoalState) -> Unit = {},
    val openJournal: (String) -> Unit = {}, val onNote: (String, String) -> Unit = { _, _ -> }, val useDate: (String, LocalDate) -> Unit = { _, _ -> },
    val recheck: (String) -> Unit = {}, val retryImages: (GoalState) -> Unit = {}, val openNotificationSettings: (() -> Unit)? = null,
    val openCommunity: (String) -> Unit = {}, val complete: (String) -> Unit = {}, val archive: (String) -> Unit = {},
    val choosePaused: () -> Unit = {},
)

@Composable
fun TodayScreen(ui: TodayUi, actions: TodayActions, now: LocalTime = LocalTime.now(), date: LocalDate = LocalDate.now()) {
    val c = LocalBridgeColors.current
    Column(Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState()).padding(start = Space.screen, end = Space.screen, top = 12.dp, bottom = 32.dp)) {
        Header(ui, now, date)
        if (actions.openNotificationSettings != null) NotificationBanner(actions.openNotificationSettings)
        if (ui.loaded && ui.paused.isNotEmpty()) PausedCard(ui.paused.size, actions.choosePaused)
        when {
            !ui.loaded -> Unit
            ui.goals.isEmpty() -> NoGoals(actions.addGoal)
            ui.showOverview -> Overview(ui, actions)
            else -> ui.open?.let { Detail(ui, it, actions) }
        }
    }
}

@Composable
private fun Header(ui: TodayUi, now: LocalTime, date: LocalDate) {
    val c = LocalBridgeColors.current
    val greeting = when (now.hour) { in 3..11 -> R.string.greeting_morning; in 12..16 -> R.string.greeting_afternoon; else -> R.string.greeting_evening }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault())).uppercase(Locale.getDefault()), style = BType.label(11f, 0.14f), color = c.mut)
            Text(stringResource(greeting, ui.name), style = BType.display(25, 1.15f), color = c.ink, modifier = Modifier.semantics { heading() })
        }
        if (ui.goals.isNotEmpty()) {
            val a11y = pluralStringResource(R.plurals.streak_a11y, ui.streak, ui.streak)
            Row(Modifier.heightIn(min = 40.dp).background(c.s1, RoundedCornerShape(20.dp)).border(1.dp, c.line, RoundedCornerShape(20.dp)).padding(start = 9.dp, end = 12.dp)
                .semantics(mergeDescendants = true) { contentDescription = a11y }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(painterResource(R.drawable.ic_fire), null, tint = c.acc, modifier = Modifier.size(19.dp))
                Text("${ui.streak}", style = BType.label(14f, 0f).copy(fontWeight = FontWeight.SemiBold), color = c.ink)
            }
        }
    }
}

@Composable
private fun NotificationBanner(onOpen: () -> Unit) {
    val c = LocalBridgeColors.current
    Row(Modifier.fillMaxWidth().padding(top = 14.dp).background(c.s1, RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.notif_banner), style = BType.body(13, lineHeight = 1.4f), color = c.ink2, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.notif_banner_cta), style = BType.body(13, FontWeight.SemiBold), color = c.ink,
            modifier = Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onOpen).padding(horizontal = 10.dp, vertical = 12.dp))
    }
}

@Composable
private fun NoGoals(onAdd: () -> Unit) {
    val c = LocalBridgeColors.current
    Column(Modifier.fillMaxWidth().padding(top = 20.dp).background(c.s1, RoundedCornerShape(20.dp)).border(1.dp, c.line, RoundedCornerShape(20.dp)).padding(Space.card), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.today_empty_title), style = BType.display(20), color = c.ink)
        Text(stringResource(R.string.today_empty_body), style = BType.body(14, lineHeight = 1.5f), color = c.mut)
    }
    AddGoalTile(onAdd, Modifier.padding(top = 12.dp))
}

@Composable
private fun AddGoalTile(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalBridgeColors.current
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).border(1.dp, c.line2, RoundedCornerShape(20.dp)).clickable(role = Role.Button, onClick = onAdd),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        Icon(painterResource(R.drawable.ic_add), null, tint = c.ink2, modifier = Modifier.size(20.dp))
        Text(stringResource(R.string.add_a_goal), style = BType.body(14, FontWeight.Medium), color = c.ink2)
    }
}

@Composable
private fun Overview(ui: TodayUi, actions: TodayActions) {
    val c = LocalBridgeColors.current
    Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.your_goals), style = BType.display(19), color = c.ink, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.your_goals_sub, ui.goals.size), style = BType.body(13), color = c.mut)
        }
        ui.goals.forEach { GoalCard(it) { actions.openGoal(it.goal.id) } }
        AddGoalTile(actions.addGoal)
    }
}

/** One card per goal: radius 24, padding 18–20, colour rotates gc1/gc2/gc3. */
@Composable
fun GoalCard(g: GoalState, onOpen: () -> Unit) {
    val c = LocalBridgeColors.current
    val p = g.progress
    val bg = c.goalCards[g.goal.colorIndex.mod(3)]
    Column(Modifier.fillMaxWidth().heightIn(min = 188.dp).clip(RoundedCornerShape(24.dp)).background(bg).clickable(role = Role.Button, onClick = onOpen).padding(18.dp),
        verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(g.goal.label, style = BType.body(14, FontWeight.Medium), color = c.goalInk, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.goal_card_day, p.day, p.total), style = BType.body(12, FontWeight.Medium), color = c.goalInk)
        }
        Spacer(Modifier.height(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text("%.1f%%".format(p.fraction * 100), style = BType.body(38, FontWeight.Medium, 1f).copy(letterSpacing = (-0.76).sp), color = c.goalInk)
                Text(if (p.tasksToday == 0) stringResource(R.string.goal_card_none) else stringResource(R.string.goal_card_today, p.doneToday, p.tasksToday), style = BType.body(13, FontWeight.Medium), color = c.goalInk)
            }
            Box(Modifier.fillMaxWidth().height(3.dp).background(c.goalTrack, RoundedCornerShape(2.dp))) { Box(Modifier.fillMaxWidth(p.fraction).height(3.dp).background(c.goalInk, RoundedCornerShape(2.dp))) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.goal_card_ends, shortDate(LocalDate.parse(g.goal.deadline))), style = BType.body(12), color = c.goalInk)
                Icon(painterResource(R.drawable.ic_arrow_forward), null, tint = c.goalInk, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun Detail(ui: TodayUi, g: GoalState, actions: TodayActions) {
    val c = LocalBridgeColors.current
    if (ui.goals.size > 1) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.heightIn(min = 44.dp).clickable(role = Role.Button) { actions.openGoal(null) }.padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_chevron_left), null, tint = c.ink, modifier = Modifier.size(24.dp))
                Text(stringResource(R.string.all_goals), style = BType.body(15, FontWeight.Medium), color = c.ink)
            }
            Spacer(Modifier.weight(1f))
            Text(g.goal.label, style = BType.display(16), color = c.ink, maxLines = 1)
            EditGoalButton { actions.editGoal(g) }
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.heightIn(min = 46.dp).background(c.accTint, RoundedCornerShape(23.dp)).border(1.dp, c.acc, RoundedCornerShape(23.dp)).clickable(role = Role.Button) { actions.editGoal(g) }.padding(start = 12.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                if (g.progress.allDone) Icon(painterResource(R.drawable.ic_check_circle), null, tint = c.ok, modifier = Modifier.size(16.dp))
                else Box(Modifier.size(8.dp).background(c.acc, CircleShape))
                Text(g.goal.label, style = BType.body(14, FontWeight.SemiBold), color = c.ink, maxLines = 1)
                Text("${g.progress.doneToday}/${g.progress.tasksToday}", style = BType.label(11f, 0f), color = c.mut)
                Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.edit_goal_a11y), tint = c.mut, modifier = Modifier.size(16.dp))
            }
            Row(Modifier.heightIn(min = 46.dp).border(1.dp, c.line, RoundedCornerShape(23.dp)).clickable(role = Role.Button, onClick = actions.addGoal).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(R.drawable.ic_add), null, tint = c.ink2, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.add_goal_chip), style = BType.body(13, FontWeight.Medium), color = c.ink2)
            }
        }
    }
    Segments(ui.view, actions.showView) { actions.openCommunity(g.goal.id) }
    AnimatedContent(ui.view, label = "view", transitionSpec = { fadeIn(tween(Motion.fade)) togetherWith fadeOut(tween(Motion.fade)) }) { v ->
        if (v == GoalView.Today) TodayView(ui, g, actions) else RealityView(ui, g, actions)
    }
}

@Composable
private fun EditGoalButton(onClick: () -> Unit) {
    val c = LocalBridgeColors.current
    Box(Modifier.size(44.dp).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.edit_goal_a11y), tint = c.mut, modifier = Modifier.size(20.dp))
    }
}

/**
 * Today | Reality Check | Community. Community opens its own screen inside the goal.
 * At very large text the three don't fit side by side, so Community moves to its own row.
 */
@Composable
private fun Segments(view: GoalView, onChange: (GoalView) -> Unit, onCommunity: () -> Unit) {
    val c = LocalBridgeColors.current
    val large = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).background(c.s0, RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(GoalView.Today to R.string.seg_today, GoalView.Reality to R.string.seg_reality).forEach { (v, label) ->
                val on = v == view
                val bg = when { !on -> Color.Transparent; v == GoalView.Today -> c.acc; else -> c.ink }
                val fg = when { !on -> c.mut; v == GoalView.Today -> c.onAcc; else -> c.bg }
                Box(Modifier.weight(1f).heightIn(min = 40.dp).background(bg, RoundedCornerShape(10.dp)).clickable(role = Role.Tab) { onChange(v) }
                    .semantics { stateDescription = if (on) "selected" else "" }, contentAlignment = Alignment.Center) {
                    Text(stringResource(label), style = BType.body(14, FontWeight.SemiBold, 1.2f), color = fg, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
            if (!large) CommunitySegment(Modifier.weight(1f), onCommunity)
        }
        if (large) CommunitySegment(Modifier.fillMaxWidth(), onCommunity)
    }
}

@Composable
private fun CommunitySegment(modifier: Modifier, onClick: () -> Unit) {
    val c = LocalBridgeColors.current
    Row(modifier.heightIn(min = 40.dp).clickable(role = Role.Button, onClick = onClick), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally)) {
        Icon(painterResource(R.drawable.ic_groups), null, tint = c.mut, modifier = Modifier.size(17.dp))
        Text(stringResource(R.string.seg_community), style = BType.body(14, FontWeight.SemiBold, 1.2f), color = c.mut, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 4.dp))
    }
}

@Composable
private fun TodayView(ui: TodayUi, g: GoalState, actions: TodayActions) {
    val c = LocalBridgeColors.current
    val p = g.progress
    val start = LocalDate.parse(g.goal.startDate); val end = LocalDate.parse(g.goal.deadline)
    val md = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        when {
            p.overdue -> RecapCard(g, actions)
            p.away -> AwayCard { actions.showView(GoalView.Reality) }
        }
        Column(Modifier.fillMaxWidth().padding(top = 22.dp).background(c.s1, RoundedCornerShape(24.dp)).border(1.dp, c.line, RoundedCornerShape(24.dp)).padding(start = 10.dp, end = 10.dp, top = 18.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val pctText = "%.1f%%".format(p.fraction * 100)
            val a11y = stringResource(R.string.bridge_a11y, pctText)
            Box(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = a11y }, contentAlignment = Alignment.TopCenter) {
                BridgeArc(p.fraction, p.todayFraction, Modifier.fillMaxWidth().widthIn(max = 340.dp).aspectRatio(340f / 196f))
                Column(Modifier.padding(top = 70.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.day_of, p.day, p.total), style = BType.label(10f, 0.16f), color = c.mut)
                    Text(pctText, style = BType.display(44, 1f).copy(letterSpacing = (-1.3).sp), color = c.ink)
                    Text(stringResource(R.string.across_bridge), style = BType.body(12), color = c.mut)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.start_label, start.format(md).uppercase()), style = BType.label(9.5f, 0.1f), color = c.mut, maxLines = 1)
                Text(stringResource(R.string.goal_label, end.format(md).uppercase()), style = BType.label(9.5f, 0.1f), color = c.accInk, maxLines = 1)
            }
            Row(Modifier.background(c.okTint, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(7.dp).background(c.okFill, CircleShape))
                Text(stringResource(R.string.done_today, p.doneToday, p.tasksToday), style = BType.body(13, FontWeight.Medium), color = c.ok)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat(stringResource(R.string.stat_streak), c.goalCards[0], Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) { Text("${p.streak}", style = BType.display(22, 1f), color = c.ink); Text(" " + pluralStringResource(R.plurals.stat_days, p.streak), style = BType.body(12), color = c.mut) }
            }
            Stat(stringResource(R.string.stat_days_left), c.goalCards[1], Modifier.weight(1f)) { Text("${p.daysLeft}", style = BType.display(22, 1f), color = c.ink) }
            Stat(stringResource(R.string.stat_drift), c.goalCards[2], Modifier.weight(1f)) {
                val d = p.drift
                Text(when { d == null -> stringResource(R.string.drift_none); d > 0 -> "+$d%"; d < 0 -> "−${abs(d)}%"; else -> "0%" }, style = BType.display(22, 1f), color = if ((d ?: 0) < 0) c.warn else c.ok)
                Text(stringResource(when { d == null || d == 0 -> R.string.drift_on; d < 0 -> R.string.drift_behind; else -> R.string.drift_ahead }), style = BType.body(11), color = c.mut)
            }
        }
        if (p.allDone) LockCard(g) { actions.openJournal(g.goal.id) }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.todays_plan), style = BType.display(19), color = c.ink, modifier = Modifier.semantics { heading() })
            Text("${p.doneToday}/${p.tasksToday}", style = BType.label(12f, 0f), color = c.mut)
        }
        if (g.tasks.isEmpty()) Column(Modifier.fillMaxWidth().border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(vertical = 28.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(R.drawable.ic_checklist), null, tint = c.mut, modifier = Modifier.size(30.dp))
            Text(stringResource(R.string.no_tasks_title), style = BType.display(16), color = c.ink)
            Text(stringResource(R.string.no_tasks_body), style = BType.body(13, lineHeight = 1.45f), color = c.mut, textAlign = TextAlign.Center)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { g.tasks.forEach { TaskRow(it, actions) } }
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).border(1.dp, c.line, RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = actions.newTask),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            Icon(painterResource(R.drawable.ic_add), null, tint = c.ink2, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.add_task), style = BType.body(14, FontWeight.Medium), color = c.ink2)
        }
    }
}

@Composable
private fun Stat(label: String, bg: Color, modifier: Modifier, value: @Composable ColumnScope.() -> Unit) {
    val c = LocalBridgeColors.current
    Column(modifier.background(if (c.isDark) c.s1 else bg, RoundedCornerShape(16.dp)).border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(12.dp).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = BType.label(9.5f, 0.12f), color = c.mut, maxLines = 1)
        value()
    }
}

/** The bridge: dashed full span, progress arc, today's inner arc and a marker where you stand. Geometry from the prototype (340×196). */
@Composable
fun BridgeArc(progress: Float, today: Float, modifier: Modifier) {
    val c = LocalBridgeColors.current
    val reduce = LocalReduceMotion.current
    val easing = CubicBezierEasing(.2f, .8f, .2f, 1f)
    val p by animateFloatAsState(progress, if (reduce) tween(0) else tween(900, easing = easing), label = "p")
    val t by animateFloatAsState(today, if (reduce) tween(0) else tween(700, easing = easing), label = "t")
    Canvas(modifier) {
        val u = size.width / 340f
        val cx = 170f * u; val cy = 176f * u; val r = 146f * u; val r2 = 128f * u
        drawLine(c.ink, Offset(6f * u, cy + 4f * u), Offset(334f * u, cy + 4f * u), strokeWidth = 1.5f * u)
        val outer = Size(2 * r, 2 * r); val outerTl = Offset(cx - r, cy - r)
        val inner = Size(2 * r2, 2 * r2); val innerTl = Offset(cx - r2, cy - r2)
        drawArc(c.mut2, 180f, 180f, false, outerTl, outer, style = Stroke(1.5f * u, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * u, 6f * u))))
        if (p > 0f) drawArc(c.prog, 180f, 180f * p, false, outerTl, outer, style = Stroke(6f * u, cap = StrokeCap.Round))
        drawArc(c.track, 180f, 180f, false, innerTl, inner, style = Stroke(10f * u, cap = StrokeCap.Round))
        if (t > 0f) drawArc(c.today, 180f, 180f * t, false, innerTl, inner, style = Stroke(10f * u, cap = StrokeCap.Round))
        val a = PI * (1 - p)
        val o = Offset(cx + r * cos(a).toFloat(), cy - r * sin(a).toFloat())
        drawCircle(c.ink, 9f * u, o); drawCircle(c.prog, 4f * u, o)
    }
}

@Composable
private fun LockCard(g: GoalState, onJournal: () -> Unit) {
    val c = LocalBridgeColors.current
    Row(Modifier.fillMaxWidth().background(c.s1, RoundedCornerShape(18.dp)).border(1.dp, if (g.journaledToday) c.ok else c.accLine, RoundedCornerShape(18.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        GoalImage(g.future?.url, null, Modifier.size(width = 56.dp, height = 68.dp).clip(RoundedCornerShape(12.dp)))
        // "Lock it in" turns into "Locked in." once today's reflection has text or a mood.
        val locked = g.journaledToday
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (locked) Icon(painterResource(R.drawable.ic_lock), null, tint = c.ok, modifier = Modifier.size(18.dp))
                Text(stringResource(if (locked) R.string.lock_done_title else R.string.lock_title), style = BType.display(15), color = c.ink)
            }
            Text(stringResource(if (locked) R.string.lock_done_sub else R.string.lock_sub), style = BType.body(13), color = c.mut)
        }
        Box(Modifier.size(44.dp).background(if (locked) c.okTint else c.acc, CircleShape).clickable(role = Role.Button, onClick = onJournal), contentAlignment = Alignment.Center) {
            Icon(painterResource(if (locked) R.drawable.ic_book else R.drawable.ic_arrow_forward), stringResource(if (locked) R.string.lock_read else R.string.lock_open), tint = if (locked) c.ok else c.onAcc, modifier = Modifier.size(22.dp))
        }
    }
}

/** Checkbox (with pop + light haptic), title, AI tag, priority pill and time. Tapping the row opens the details sheet. */
@Composable
fun TaskRow(t: PlanTask, actions: TodayActions) {
    val c = LocalBridgeColors.current
    val view = LocalView.current
    val reduce = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    val pop = remember { Animatable(1f) }
    val title = t.task.title
    val toggleLabel = stringResource(if (t.done) R.string.untoggle_task_a11y else R.string.toggle_task_a11y, title)
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).background(c.s1, RoundedCornerShape(16.dp)).border(1.dp, c.line, RoundedCornerShape(16.dp))
        .clickable(role = Role.Button) { actions.openTask(t) }.padding(start = 8.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(44.dp).clickable(role = Role.Checkbox, onClickLabel = toggleLabel) {
            val done = !t.done
            if (done) {
                view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CLOCK_TICK)
                if (!reduce) scope.launch { pop.animateTo(1.3f, tween(126)); pop.animateTo(1f, tween(154)) }
            }
            actions.toggle(t.task.id, done)
        }.semantics { contentDescription = toggleLabel; stateDescription = if (t.done) "done" else "not done" }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(26.dp).scale(pop.value).background(if (t.done) c.okFill else Color.Transparent, CircleShape).border(2.dp, if (t.done) c.okFill else c.ink2, CircleShape), contentAlignment = Alignment.Center) {
                if (t.done) Icon(painterResource(R.drawable.ic_check), null, tint = c.onOk, modifier = Modifier.size(18.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (t.isAi) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(5.dp).background(c.acc, CircleShape))
                Text(stringResource(R.string.ai_label), style = BType.label(9.5f, 0.1f), color = c.acc)
            }
            Text(title, style = BType.body(15, FontWeight.Medium, 1.35f), color = if (t.done) c.mut else c.ink, textDecoration = if (t.done) TextDecoration.LineThrough else null)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PriorityPill(t.task.priority)
                t.task.timeOfDay?.let { Text(it, style = BType.body(12), color = c.mut) }
            }
        }
        Icon(painterResource(R.drawable.ic_chevron_right), null, tint = c.mut2, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun RealityView(ui: TodayUi, g: GoalState, actions: TodayActions) {
    val c = LocalBridgeColors.current
    var note by rememberSaveable(g.goal.id) { mutableStateOf(g.goal.realityNote.orEmpty()) }
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        g.feasibility?.let { f ->
            FeasibilityCard(f, LocalDate.parse(g.goal.deadline), onUseDate = { actions.useDate(g.goal.id, it) },
                note = when { ui.realityBusy -> stringResource(R.string.verdict_checking); ui.realityError -> stringResource(R.string.verdict_refresh_failed); else -> null })
        }
        Box(Modifier.fillMaxWidth().height(430.dp).clip(RoundedCornerShape(22.dp)).background(c.bg).border(1.dp, c.line, RoundedCornerShape(22.dp))) {
            val img = g.reality
            GoalImage(img?.url?.takeIf { img.status == "done" }, stringResource(R.string.reality_title), Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to c.bg.copy(alpha = .92f))))
            Text(stringResource(R.string.reality_kicker), style = BType.label(10f, 0.16f), color = c.mut, modifier = Modifier.padding(18.dp))
            Column(Modifier.align(Alignment.BottomStart).padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (img?.status) {
                    "done" -> Unit
                    "failed", null -> Text(stringResource(R.string.reality_image_failed) + "  ", style = BType.body(13), color = c.mut)
                    else -> Text(stringResource(R.string.reality_image_pending), style = BType.body(13), color = c.mut)
                }
                if (img?.status == "failed" || img == null) Text(stringResource(R.string.reality_image_retry), style = BType.body(13, FontWeight.SemiBold), color = c.ink,
                    modifier = Modifier.heightIn(min = 44.dp).clickable(role = Role.Button) { actions.retryImages(g) }.padding(vertical = 12.dp))
                Text(stringResource(R.string.reality_title), style = BType.display(30, 1.08f), color = c.ink)
                Text(stringResource(R.string.reality_sub), style = BType.body(14), color = c.ink2)
            }
        }
        if (!g.goal.routine.isNullOrBlank()) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.reality_routine), style = BType.label(11f, 0.14f), color = c.mut)
            Row { Box(Modifier.width(1.dp).heightIn(min = 20.dp).background(c.line)); Text(g.goal.routine, style = BType.body(15, lineHeight = 1.6f), color = c.ink2, modifier = Modifier.padding(start = 14.dp)) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.reality_why), style = BType.label(11f, 0.14f), color = c.mut)
            BigTextArea(note, { note = it.take(1000); actions.onNote(g.goal.id, note) }, stringResource(R.string.reality_why_hint), stringResource(R.string.reality_why), minHeight = 96)
        }
        SecondaryButton(stringResource(R.string.reality_back), { actions.showView(GoalView.Today) })
    }
}

// ---------------- Sheets ----------------

@Composable
fun TaskSheet(t: PlanTask?, onSave: (title: String, notes: String, priority: String, time: String) -> Unit, onToggle: (Boolean) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val c = LocalBridgeColors.current
    var title by rememberSaveable { mutableStateOf(t?.task?.title.orEmpty()) }
    var notes by rememberSaveable { mutableStateOf(t?.task?.notes.orEmpty()) }
    var priority by rememberSaveable { mutableStateOf(t?.task?.priority ?: "Medium") }
    var time by rememberSaveable { mutableStateOf(t?.task?.timeOfDay.orEmpty()) }
    var error by rememberSaveable { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    val readOnly = t?.readOnly == true
    BridgeSheet(onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SheetTitle(stringResource(if (t == null) R.string.add_task_title else R.string.task_details))
                if (t?.isAi == true) Row(Modifier.heightIn(min = 26.dp).background(c.accTint, RoundedCornerShape(13.dp)).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(5.dp).background(c.acc, CircleShape)); Text(stringResource(R.string.ai_suggested), style = BType.label(10f, 0.1f), color = c.accInk)
                }
            }
            if (readOnly) Text(stringResource(R.string.shared_task_note), style = BType.body(13), color = c.mut)
            BridgeField(stringResource(R.string.field_title), title, { title = it.take(120); error = false }, stringResource(R.string.add_task_hint), error = if (error) stringResource(R.string.task_name_err) else null)
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(stringResource(R.string.field_notes), style = BType.label(11f, 0.12f), color = c.mut)
                BigTextArea(notes, { notes = it.take(2000) }, stringResource(R.string.field_optional), stringResource(R.string.field_notes), minHeight = 76)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1.4f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(stringResource(R.string.field_priority), style = BType.label(11f, 0.12f), color = c.mut)
                    PrioritySelector(priority, { priority = it })
                }
                BridgeField(stringResource(R.string.field_when), time, { time = it.take(24) }, stringResource(R.string.field_when_hint), Modifier.weight(1f))
            }
            t?.task?.rationale?.let {
                Column(Modifier.fillMaxWidth().background(c.accTint, RoundedCornerShape(14.dp)).border(1.dp, c.accLine, RoundedCornerShape(14.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.why_this_task), style = BType.label(10f, 0.14f), color = c.acc)
                    Text(it, style = BType.body(14, lineHeight = 1.5f), color = c.ink2)
                }
            }
            val save = { if (title.trim().length < 2) error = true else onSave(title, notes, priority, time) }
            when {
                t == null -> PrimaryButton(stringResource(R.string.add_to_today), save)
                confirm -> Column(Modifier.fillMaxWidth().background(c.badTint, RoundedCornerShape(16.dp)).border(1.dp, c.bad, RoundedCornerShape(16.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.delete_task_title), style = BType.display(16), color = c.ink)
                    Text(stringResource(R.string.delete_task_body), style = BType.body(13), color = c.ink2)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(stringResource(R.string.cancel), { confirm = false }, Modifier.weight(1f))
                        PrimaryButton(stringResource(R.string.delete), onDelete, Modifier.weight(1f), container = c.bad, content = c.ink)
                    }
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!readOnly) PrimaryButton(stringResource(R.string.save_changes), save)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(stringResource(if (t.done) R.string.mark_not_done else R.string.mark_done), { onToggle(!t.done) }, Modifier.weight(1f))
                        if (!readOnly) Box(Modifier.weight(1f).heightIn(min = 48.dp).border(1.dp, c.bad, RoundedCornerShape(14.dp)).clickable(role = Role.Button) { confirm = true }, contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.delete), style = BType.body(14, FontWeight.Medium), color = c.bad)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EditGoalSheet(g: GoalState, canArchive: Boolean, onSave: (String, LocalDate) -> Unit, onArchive: () -> Unit, onDismiss: () -> Unit, onComplete: (() -> Unit)? = null) {
    val c = LocalBridgeColors.current
    var label by rememberSaveable { mutableStateOf(g.goal.label) }
    var deadline by rememberSaveable { mutableStateOf(g.goal.deadline) }
    var error by rememberSaveable { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    val today = LocalDate.parse(app.thebridge.data.local.LocalDay.today())
    val picked = LocalDate.parse(deadline)
    BridgeSheet(onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SheetTitle(stringResource(R.string.edit_goal))
            Text(g.goal.statement, style = BType.body(14, lineHeight = 1.45f), color = c.mut)
            BridgeField(stringResource(R.string.field_name), label, { label = it.take(24); error = false }, "", error = if (error) stringResource(R.string.review_name_err) else null)
            DateField(stringResource(R.string.target_date), picked, today, Deadlines.MIN_DAYS_EDIT, { deadline = it.toString() })
            if (Deadlines.check(picked, today, Deadlines.MIN_DAYS_EDIT) != null) DeadlineMessage(picked, today, Deadlines.MIN_DAYS_EDIT)
            if (confirm) Column(Modifier.fillMaxWidth().background(c.badTint, RoundedCornerShape(16.dp)).border(1.dp, c.bad, RoundedCornerShape(16.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.archive_title), style = BType.display(16), color = c.ink)
                Text(stringResource(R.string.archive_body), style = BType.body(13, lineHeight = 1.45f), color = c.ink2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(stringResource(R.string.cancel), { confirm = false }, Modifier.weight(1f))
                    PrimaryButton(stringResource(R.string.archive), onArchive, Modifier.weight(1f), container = c.bad, content = c.ink)
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(stringResource(R.string.save_changes), {
                    when {
                        label.trim().length < 2 -> error = true
                        Deadlines.check(picked, today, Deadlines.MIN_DAYS_EDIT) == null -> onSave(label, picked)
                    }
                })
                if (onComplete != null) Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).border(1.dp, c.ok, RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onComplete), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.mark_complete), style = BType.body(14, FontWeight.Medium), color = c.ok)
                }
                if (canArchive) Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).border(1.dp, c.bad, RoundedCornerShape(14.dp)).clickable(role = Role.Button) { confirm = true }, contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.archive_goal), style = BType.body(14, FontWeight.Medium), color = c.bad)
                }
            }
        }
    }
}

@Composable
fun LimitSheet(onManage: () -> Unit, onDismiss: () -> Unit, onUpgrade: (() -> Unit)? = null) {
    val c = LocalBridgeColors.current
    BridgeSheet(onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(48.dp).background(c.accTint, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Text("3", style = BType.display(22), color = c.acc) }
            SheetTitle(stringResource(R.string.limit_title))
            Text(stringResource(R.string.limit_body), style = BType.body(15, lineHeight = 1.5f), color = c.ink2)
            PrimaryButton(stringResource(R.string.limit_manage), onManage)
            if (onUpgrade != null) SecondaryButton(stringResource(R.string.limit_upgrade), onUpgrade)
            SecondaryButton(stringResource(R.string.consent_decline), onDismiss)
        }
    }
}

/** Plus ended with more than three goals: the extras are paused and read-only until the user picks. */
@Composable
private fun PausedCard(count: Int, onChoose: () -> Unit) {
    val c = LocalBridgeColors.current
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).background(c.warnTint, RoundedCornerShape(18.dp)).border(1.dp, c.warn, RoundedCornerShape(18.dp)).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.paused_title), style = BType.display(16), color = c.ink, modifier = Modifier.semantics { heading() })
        Text(pluralStringResource(R.plurals.paused_body, count, count), style = BType.body(13, lineHeight = 1.45f), color = c.ink2)
        Row(Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onChoose), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.paused_choose), style = BType.body(14, FontWeight.SemiBold), color = c.ink)
            Icon(painterResource(R.drawable.ic_arrow_forward), null, tint = c.ink, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun ChooseGoalsSheet(goals: List<GoalEntity>, initial: Set<String>, max: Int, onSave: (Set<String>) -> Unit, onUpgrade: (() -> Unit)?, onDismiss: () -> Unit) {
    BridgeSheet(onDismiss) { ChooseGoalsContent(goals, initial, max, onSave, onUpgrade) }
}

/** Pick up to [max] goals to keep active; the rest stay paused with their history. */
@Composable
fun ChooseGoalsContent(goals: List<GoalEntity>, initial: Set<String>, max: Int, onSave: (Set<String>) -> Unit, onUpgrade: (() -> Unit)?) {
    val c = LocalBridgeColors.current
    var keep by rememberSaveable { mutableStateOf(initial.take(max).toSet()) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SheetTitle(stringResource(R.string.choose_title))
        Text(stringResource(R.string.choose_body, max), style = BType.body(14, lineHeight = 1.45f), color = c.ink2)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            goals.forEach { g ->
                val on = g.id in keep
                val enabled = on || keep.size < max
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).background(if (on) c.accTint else c.s1, RoundedCornerShape(14.dp)).border(1.dp, if (on) c.acc else c.line, RoundedCornerShape(14.dp))
                    .toggleable(on, enabled = enabled, role = Role.Checkbox) { keep = if (it) keep + g.id else keep - g.id }.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(24.dp).background(if (on) c.acc else Color.Transparent, RoundedCornerShape(7.dp)).border(1.5.dp, if (on) c.acc else c.mut2, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                        if (on) Icon(painterResource(R.drawable.ic_check), null, tint = c.onAcc, modifier = Modifier.size(16.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(g.label, style = BType.body(15, FontWeight.SemiBold), color = if (enabled) c.ink else c.mut)
                        Text(g.statement, style = BType.body(12), color = c.mut, maxLines = 2)
                    }
                }
            }
        }
        Text(stringResource(R.string.choose_count, keep.size, max), style = BType.label(11f, 0.1f), color = c.mut)
        PrimaryButton(stringResource(R.string.save_changes), { onSave(keep) }, enabled = keep.isNotEmpty())
        if (onUpgrade != null) SecondaryButton(stringResource(R.string.limit_upgrade), onUpgrade)
    }
}

/** The deadline has passed: extend, mark complete or archive. Whatever happened, no guilt. */
@Composable
private fun RecapCard(g: GoalState, actions: TodayActions) {
    val c = LocalBridgeColors.current
    val md = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    val n = g.progress.activeDays
    Column(Modifier.fillMaxWidth().padding(top = 22.dp).background(c.s1, RoundedCornerShape(20.dp)).border(1.dp, c.accLine, RoundedCornerShape(20.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.recap_kicker, LocalDate.parse(g.goal.deadline).format(md).uppercase()), style = BType.label(10f, 0.14f), color = c.accInk)
        Text(stringResource(R.string.recap_title), style = BType.display(20), color = c.ink, modifier = Modifier.semantics { heading() })
        Text(pluralStringResource(R.plurals.recap_body, n, n), style = BType.body(14, lineHeight = 1.45f), color = c.ink2)
        Spacer(Modifier.height(4.dp))
        PrimaryButton(stringResource(R.string.recap_complete), { actions.complete(g.goal.id) })
        SecondaryButton(stringResource(R.string.recap_extend), { actions.editGoal(g) })
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { actions.archive(g.goal.id) }, contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.recap_archive), style = BType.body(14, FontWeight.Medium), color = c.mut)
        }
    }
}

/** Three or more days missed: pick up with today's plan only. */
@Composable
private fun AwayCard(onAdjust: () -> Unit) {
    val c = LocalBridgeColors.current
    Column(Modifier.fillMaxWidth().padding(top = 22.dp).background(c.s1, RoundedCornerShape(20.dp)).border(1.dp, c.line, RoundedCornerShape(20.dp)).padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.away_title), style = BType.display(20), color = c.ink, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.away_body), style = BType.body(14, lineHeight = 1.45f), color = c.ink2)
        Row(Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onAdjust), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.away_adjust), style = BType.body(14, FontWeight.Medium), color = c.accInk)
            Icon(painterResource(R.drawable.ic_arrow_forward), null, tint = c.accInk, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun ArchiveGoalSheet(onArchive: () -> Unit, onDismiss: () -> Unit) {
    val c = LocalBridgeColors.current
    BridgeSheet(onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SheetTitle(stringResource(R.string.archive_title))
            Text(stringResource(R.string.archive_body), style = BType.body(15, lineHeight = 1.5f), color = c.ink2)
            PrimaryButton(stringResource(R.string.archive), onArchive, container = c.bad, content = c.ink)
            SecondaryButton(stringResource(R.string.cancel), onDismiss)
        }
    }
}

/** The goal was marked complete: the future self, the days it took, and the next goal. */
@Composable
fun GoalCrossedScreen(g: GoalState, onNext: () -> Unit, onClose: () -> Unit) {
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) { GoalCrossedContent(g, onNext, onClose) }
}

@Composable
fun GoalCrossedContent(g: GoalState, onNext: () -> Unit, onClose: () -> Unit) {
    val c = LocalBridgeColors.current
    val n = g.progress.activeDays
    Column(Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(470.dp)) {
            GoalImage(g.future?.url, g.goal.statement, Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to c.bg)))
            Text(stringResource(R.string.future_self), style = BType.label(10f, 0.16f), color = c.accInk, modifier = Modifier.padding(start = 24.dp, top = 64.dp))
        }
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.crossed_kicker, g.goal.label.uppercase()), style = BType.label(11f, 0.14f), color = c.ok)
            Text(stringResource(R.string.crossed_title), style = BType.display(34, 1.08f), color = c.ink, modifier = Modifier.semantics { heading() })
            Text(g.goal.statement, style = BType.body(15, FontWeight.Medium, 1.45f), color = c.ink2)
            Text(pluralStringResource(R.plurals.crossed_body, n, n), style = BType.body(15, lineHeight = 1.5f), color = c.mut)
            Spacer(Modifier.height(20.dp))
            PrimaryButton(stringResource(R.string.crossed_next), onNext)
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClose), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.crossed_back), style = BType.body(15, FontWeight.Medium), color = c.ink2)
            }
        }
    }
}

/** Full-screen moment when the last task of the day is ticked. */
@Composable
fun CelebrateScreen(g: GoalState, streak: Int, next: GoalState?, onNext: () -> Unit, onJournal: () -> Unit, onClose: () -> Unit) {
    val c = LocalBridgeColors.current
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(470.dp)) {
                GoalImage(g.future?.url, g.goal.statement, Modifier.matchParentSize())
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to c.bg)))
                Text(stringResource(R.string.future_self), style = BType.label(10f, 0.16f), color = c.accInk, modifier = Modifier.padding(start = 24.dp, top = 64.dp))
            }
            Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.celebrate_kicker, g.goal.label.uppercase(), g.progress.day), style = BType.label(11f, 0.14f), color = c.ok)
                Text(stringResource(R.string.celebrate_title), style = BType.display(34, 1.08f), color = c.ink, modifier = Modifier.semantics { heading() })
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("$streak", style = BType.display(64, 1f).copy(fontWeight = FontWeight.Bold), color = c.accInk)
                    Text(stringResource(R.string.celebrate_streak), style = BType.body(15, FontWeight.Medium), color = c.ink2, modifier = Modifier.padding(bottom = 8.dp))
                }
                val left = (g.progress.total - g.progress.day).coerceAtLeast(0)
                Text(pluralStringResource(R.plurals.celebrate_days_to_go, left, left) + " " + stringResource(R.string.celebrate_body), style = BType.body(15, lineHeight = 1.5f), color = c.mut)
                Spacer(Modifier.height(20.dp))
                if (next != null) Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).background(c.s1, RoundedCornerShape(16.dp)).border(1.dp, c.line, RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onNext).padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    val n = next.progress.tasksToday - next.progress.doneToday
                    Text(stringResource(R.string.celebrate_next, next.goal.label, n), style = BType.body(15, FontWeight.Medium), color = c.ink)
                    Icon(painterResource(R.drawable.ic_arrow_forward), null, tint = c.ink, modifier = Modifier.size(20.dp))
                }
                PrimaryButton(stringResource(R.string.celebrate_journal), onJournal)
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClose), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.celebrate_back), style = BType.body(15, FontWeight.Medium), color = c.ink2)
                }
            }
        }
    }
}
