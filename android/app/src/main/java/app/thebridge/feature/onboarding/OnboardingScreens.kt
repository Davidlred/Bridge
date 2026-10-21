package app.thebridge.feature.onboarding

import app.thebridge.ui.components.LiveTextField
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.thebridge.R
import app.thebridge.data.remote.Feasibility
import app.thebridge.domain.Deadlines
import app.thebridge.domain.GoalText
import app.thebridge.ui.components.BackButton
import app.thebridge.ui.components.BigTextArea
import app.thebridge.ui.components.BridgeSheet
import app.thebridge.ui.components.DateField
import app.thebridge.ui.components.GoalImage
import app.thebridge.ui.components.PrimaryButton
import app.thebridge.ui.components.SecondaryButton
import app.thebridge.ui.components.SheetTitle
import app.thebridge.ui.components.StepHeader
import app.thebridge.ui.components.StepTitle
import app.thebridge.ui.components.WarnNote
import app.thebridge.ui.components.shortDate
import app.thebridge.ui.theme.BType
import app.thebridge.ui.theme.LocalBridgeColors
import app.thebridge.ui.theme.LocalReduceMotion
import app.thebridge.ui.theme.Motion
import app.thebridge.ui.theme.Radius
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** Steps 1–4 and the plan review. [adding] = a second or third goal; [onExit] leaves without creating anything. */
@Composable
fun OnboardingFlow(adding: Boolean, onExit: (() -> Unit)?, onFinished: () -> Unit, vm: OnboardingViewModel = hiltViewModel()) {
    LaunchedEffect(adding) { vm.open(adding) }
    val s by vm.state.collectAsStateWithLifecycle()
    val ui = s ?: return
    val reduce = LocalReduceMotion.current
    val back: (() -> Unit)? = when {
        ui.draft.step != Step.Goal -> vm::back
        onExit != null -> { { vm.discard(); onExit() } }
        else -> null
    }
    BackHandler(enabled = back != null) { back?.invoke() }
    val examples = Pair(stringResource(R.string.ob_goal_example), stringResource(R.string.ob_routine_example))

    AnimatedContent(ui.draft.step, label = "step", transitionSpec = {
        if (reduce) fadeIn(tween(Motion.fade)) togetherWith fadeOut(tween(Motion.fade))
        else (slideInHorizontally(tween(380, easing = CubicBezierEasing(.2f, .8f, .2f, 1f))) { 28 } + fadeIn(tween(380))) togetherWith fadeOut(tween(120))
    }) { step ->
        val c = LocalBridgeColors.current
        Box(Modifier.fillMaxSize().background(c.bg).systemBarsPadding().imePadding()) {
            when (step) {
                Step.Goal -> GoalStep(ui, back, vm::onGoal, { vm.useExampleGoal(examples.first) }, vm::onDeadline, vm::toRoutine)
                Step.Routine -> RoutineStep(ui, vm::back, vm::onRoutine, { vm.useExampleRoutine(examples.second) }, vm::toPhoto)
                Step.Photo -> PhotoStep(ui, vm::back, vm::uploadPhoto, vm::removePhoto, vm::generate, vm::skipPhoto)
                Step.Generating -> GeneratingStep(ui, vm::retry, vm::writeOwnTasks, vm::continueWithoutImages, vm::cancelGeneration)
                Step.Review -> ReviewStep(ui, vm::onLabel, vm::onDeadline, vm::onTaskTitle, vm::deleteTask, vm::addTask) { vm.start(onFinished) }
            }
        }
    }
}

@Composable
private fun StepPage(scroll: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier).padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp), content = content,
    )
}

@Composable
private fun Counter(left: Int, max: Int) {
    val c = LocalBridgeColors.current
    if (left <= max / 10) Text("$left", style = BType.label(12f, 0f), color = c.warn)
}

@Composable
private fun ExampleLink(onClick: () -> Unit) =
    Text(stringResource(R.string.use_example), style = BType.body(13, FontWeight.Medium), color = LocalBridgeColors.current.ink,
        modifier = Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 12.dp))

@Composable
fun GoalStep(ui: OnboardingUi, onBack: (() -> Unit)?, onGoal: (String) -> Unit, onExample: () -> Unit, onDeadline: (LocalDate) -> Unit, onContinue: () -> Unit) {
    val c = LocalBridgeColors.current
    val d = ui.draft
    StepPage {
        StepHeader(1, onBack)
        StepTitle(stringResource(R.string.ob_goal_title))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BigTextArea(d.goal, onGoal, stringResource(R.string.ob_goal_hint), stringResource(R.string.ob_goal_a11y), large = true, maxLength = GoalText.MAX_LENGTH)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                ExampleLink(onExample); Counter(GoalText.MAX_LENGTH - d.goal.length, GoalText.MAX_LENGTH)
            }
        }
        Text(stringResource(R.string.ob_goal_help), style = BType.body(14, lineHeight = 1.5f), color = c.mut)
        if (GoalText.hasDate(d.goal)) WarnNote(stringResource(R.string.ob_goal_has_date), R.drawable.ic_event_busy)
        else if (GoalText.needsNumber(d.goal)) WarnNote(stringResource(R.string.ob_goal_needs_number), R.drawable.ic_straighten)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DateField(stringResource(R.string.target_date), ui.deadline, ui.today, Deadlines.MIN_DAYS_NEW, onDeadline)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(1L to R.string.chip_1_month, 3L to R.string.chip_3_months, 6L to R.string.chip_6_months, 12L to R.string.chip_1_year).forEach { (m, label) ->
                    val value = ui.today.plusMonths(m)
                    val on = ui.deadline == value
                    Box(Modifier.heightIn(min = 36.dp).background(if (on) c.accTint else Color.Transparent, RoundedCornerShape(18.dp))
                        .border(1.dp, if (on) c.accLine else c.line, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).clickable(role = Role.Button) { onDeadline(value) }.padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center) { Text(stringResource(label), style = BType.body(12, FontWeight.Medium), color = if (on) c.accInk else c.ink2, maxLines = 1) }
                }
            }
            DeadlineMessage(ui.deadline, ui.today, Deadlines.MIN_DAYS_NEW)
        }
        Spacer(Modifier.weight(1f))
        PrimaryButton(stringResource(R.string.continue_), onContinue, enabled = ui.goalReady)
    }
}

@Composable
fun DeadlineMessage(deadline: LocalDate?, today: LocalDate, minDays: Int) {
    val c = LocalBridgeColors.current
    if (deadline == null) return
    when (Deadlines.check(deadline, today, minDays)) {
        null -> { val n = ChronoUnit.DAYS.between(today, deadline).toInt(); Text(stringResource(R.string.date_info, n), style = BType.body(13), color = c.mut) }
        Deadlines.Problem.TooSoonWeek -> Text(stringResource(R.string.date_err_week), style = BType.body(13), color = c.bad)
        Deadlines.Problem.TooSoonDay -> Text(stringResource(R.string.date_err_day), style = BType.body(13), color = c.bad)
        Deadlines.Problem.TooFar -> Text(stringResource(R.string.date_err_far), style = BType.body(13), color = c.bad)
        Deadlines.Problem.Missing -> Text(stringResource(R.string.date_err_missing), style = BType.body(13), color = c.bad)
    }
}

@Composable
fun RoutineStep(ui: OnboardingUi, onBack: () -> Unit, onRoutine: (String) -> Unit, onExample: () -> Unit, onContinue: () -> Unit) {
    val c = LocalBridgeColors.current
    StepPage {
        StepHeader(2, onBack)
        StepTitle(stringResource(R.string.ob_routine_title))
        if (ui.draft.routineFromSetup) Row(Modifier.fillMaxWidth().background(c.s1, RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(R.drawable.ic_history), null, tint = c.mut, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.ob_routine_kept), style = BType.body(13, lineHeight = 1.45f), color = c.ink2)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BigTextArea(ui.draft.routine, onRoutine, stringResource(R.string.ob_routine_hint), stringResource(R.string.ob_routine_a11y), minHeight = 200, maxLength = 1000)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                ExampleLink(onExample); Counter(1000 - ui.draft.routine.length, 1000)
            }
        }
        Text(stringResource(R.string.ob_routine_help), style = BType.body(14, lineHeight = 1.5f), color = c.mut)
        Spacer(Modifier.weight(1f))
        PrimaryButton(stringResource(R.string.continue_), onContinue, enabled = ui.routineReady)
    }
}

@Composable
fun PhotoStep(ui: OnboardingUi, onBack: () -> Unit, onUpload: (Uri) -> Unit, onRemove: () -> Unit, onGenerate: () -> Unit, onSkip: () -> Unit) {
    val c = LocalBridgeColors.current
    val ctx = LocalContext.current
    var consent by rememberSaveable { mutableStateOf<String?>(null) }       // "picker" | "camera" while the consent sheet is open
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(onUpload) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraUri?.let { onUpload(Uri.parse(it)) } }
    fun launch(source: String) {
        if (source == "camera") {
            val file = File(ctx.cacheDir, "camera").apply { mkdirs() }.let { File(it, "photo.jpg") }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
            cameraUri = uri.toString(); camera.launch(uri)
        } else picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    StepPage(scroll = true) {
        StepHeader(3, onBack)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StepTitle(stringResource(R.string.ob_photo_title))
            Text(stringResource(R.string.ob_photo_body), style = BType.body(15, lineHeight = 1.5f), color = c.mut)
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 300.dp).clip(RoundedCornerShape(24.dp)).background(c.s1).dashedBorder(c.line)) {
            when (ui.draft.photo) {
                PhotoState.None -> Column(Modifier.matchParentSize().background(c.bg).clickable(role = Role.Button) { consent = "picker" }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
                    Box(Modifier.size(64.dp).background(c.accTint, CircleShape), contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_photo_camera), null, tint = c.ink2, modifier = Modifier.size(30.dp)) }
                    Text(stringResource(R.string.ob_photo_pick), style = BType.display(16), color = c.ink)
                    Text(stringResource(R.string.ob_photo_pick_sub), style = BType.body(13), color = c.mut)
                    Text(stringResource(R.string.ob_photo_camera), style = BType.body(13, FontWeight.SemiBold), color = c.ink,
                        modifier = Modifier.heightIn(min = 44.dp).clickable(role = Role.Button) { consent = "camera" }.padding(horizontal = 12.dp, vertical = 12.dp))
                }
                PhotoState.Uploading -> Column(Modifier.matchParentSize().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
                    Text(stringResource(R.string.ob_photo_uploading), style = BType.label(11f, 0.14f), color = c.mut)
                    CircularProgressIndicator(color = c.acc, trackColor = c.track, modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                }
                PhotoState.Done -> Box(Modifier.matchParentSize()) {
                    GoalImage(ui.draft.photoUrl, stringResource(R.string.ob_photo_a11y), Modifier.matchParentSize())
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                        Row(Modifier.heightIn(min = 28.dp).background(c.okTint, RoundedCornerShape(14.dp)).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(painterResource(R.drawable.ic_check), null, tint = c.ok, modifier = Modifier.size(15.dp))
                            Text(stringResource(R.string.ob_photo_uploaded), style = BType.label(11f, 0.08f), color = c.ok)
                        }
                        Box(Modifier.size(44.dp).offset(8.dp, (-8).dp).background(c.s1.copy(alpha = .8f), CircleShape).clip(CircleShape).clickable(role = Role.Button, onClick = onRemove), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.ob_photo_remove), tint = c.ink, modifier = Modifier.size(22.dp))
                        }
                    }
                    Box(Modifier.align(Alignment.BottomCenter).padding(16.dp).heightIn(min = 40.dp).background(c.s1, RoundedCornerShape(20.dp)).border(1.dp, c.line, RoundedCornerShape(20.dp))
                        .clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button) { consent = "picker" }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.ob_photo_replace), style = BType.body(13, FontWeight.Medium), color = c.ink)
                    }
                }
                PhotoState.Failed -> Column(Modifier.matchParentSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
                    Icon(painterResource(R.drawable.ic_error), null, tint = c.bad, modifier = Modifier.size(34.dp))
                    Text(stringResource(R.string.ob_photo_failed), style = BType.display(17), color = c.ink, textAlign = TextAlign.Center)
                    Text(stringResource(R.string.ob_photo_failed_sub), style = BType.body(13, lineHeight = 1.45f), color = c.mut, textAlign = TextAlign.Center)
                    Box(Modifier.heightIn(min = 44.dp).border(1.dp, c.line, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).clickable(role = Role.Button) { consent = "picker" }.padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.ob_photo_retry), style = BType.body(14, FontWeight.SemiBold), color = c.ink)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(R.drawable.ic_lock), null, tint = c.mut, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.ob_photo_privacy), style = BType.body(13, lineHeight = 1.45f), color = c.mut)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(stringResource(R.string.ob_generate), onGenerate, enabled = ui.draft.photo == PhotoState.Done)
            Column(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onSkip).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.ob_skip_photo), style = BType.body(14, FontWeight.Medium), color = c.ink2)
                Text(stringResource(R.string.ob_skip_photo_sub), style = BType.body(12), color = c.mut, textAlign = TextAlign.Center)
            }
        }
    }
    consent?.let { source ->
        ConsentSheet(onAgree = { consent = null; launch(source) }, onDecline = { consent = null })
    }
}

private fun Modifier.dashedBorder(color: Color) = this.then(Modifier.border(1.5.dp, color, RoundedCornerShape(24.dp)))

/** Shown before any photo leaves the device: what happens to it, and how to take it back. */
@Composable
fun ConsentSheet(onAgree: () -> Unit, onDecline: () -> Unit) {
    val c = LocalBridgeColors.current
    BridgeSheet(onDecline) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SheetTitle(stringResource(R.string.consent_title))
            Text(stringResource(R.string.consent_body), style = BType.body(15, lineHeight = 1.5f), color = c.ink2)
            listOf(R.string.consent_point_private, R.string.consent_point_training, R.string.consent_point_delete).forEach {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(painterResource(R.drawable.ic_check), null, tint = c.ok, modifier = Modifier.size(18.dp))
                    Text(stringResource(it), style = BType.body(14, lineHeight = 1.45f), color = c.ink)
                }
            }
            PrimaryButton(stringResource(R.string.consent_agree), onAgree)
            SecondaryButton(stringResource(R.string.consent_decline), onDecline)
        }
    }
}

@Composable
fun GeneratingStep(ui: OnboardingUi, onRetry: () -> Unit, onOwnTasks: () -> Unit, onContinueWithoutImages: () -> Unit, onCancel: () -> Unit) {
    val c = LocalBridgeColors.current
    val d = ui.draft
    val noPhoto = d.photo != PhotoState.Done
    val planFailed = d.plan == PlanState.Failed || d.plan == PlanState.Quota
    val failed = planFailed || ui.imagesFailed
    val done = d.plan == PlanState.Done && !ui.imagesRunning && !ui.imagesFailed
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(done, failed) { while (!done && !failed) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(500) } }
    val elapsed = (now - d.genStartedAt).coerceAtLeast(0)
    val pct = when { done -> 1f; failed -> .66f; else -> minOf(.96f, elapsed / 54_000f) }

    if (d.plan == PlanState.Clarify) { Clarify(d.clarification.orEmpty(), onCancel); return }
    StepPage {
        StepHeader(4, null)
        Scanner(failed, done, noPhoto)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(when { failed -> R.string.gen_title_failed; done -> R.string.gen_title_done; else -> R.string.gen_title }), style = BType.display(26, 1.15f), color = c.ink, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            Text(
                when {
                    d.plan == PlanState.Quota -> stringResource(R.string.gen_quota)
                    failed -> stringResource(R.string.gen_sub_failed)
                    done -> stringResource(R.string.gen_sub_done)
                    else -> { val left = maxOf(1, (20 * (1 - pct)).roundToInt()); pluralStringResource(R.plurals.gen_seconds_left, left, left) }
                },
                style = BType.body(14, lineHeight = 1.45f), color = c.mut, textAlign = TextAlign.Center, modifier = Modifier.width(300.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(3.dp).background(c.track, RoundedCornerShape(2.dp))) { Box(Modifier.fillMaxWidth(pct).height(3.dp).background(c.acc, RoundedCornerShape(2.dp))) }
        Column(Modifier.fillMaxWidth().background(c.s1, RoundedCornerShape(18.dp)).border(1.dp, c.line, RoundedCornerShape(18.dp))) {
            GenRow(R.string.gen_row_plan, R.string.gen_row_plan_sub, when (d.plan) { PlanState.Done -> "done"; PlanState.Running -> "running"; PlanState.Pending -> "pending"; else -> "failed" })
            HorizontalDivider(color = c.line)
            GenRow(R.string.gen_row_future, if (noPhoto) R.string.gen_row_future_concept else R.string.gen_row_future_face, imageStatus(ui, "future"))
            HorizontalDivider(color = c.line)
            GenRow(R.string.gen_row_reality, R.string.gen_row_reality_sub, imageStatus(ui, "reality"))
        }
        if (failed) {
            PrimaryButton("↻  " + stringResource(R.string.retry), onRetry)
            if (planFailed) SecondaryButton(stringResource(R.string.gen_own_tasks), onOwnTasks)
            else if (d.plan == PlanState.Done) SecondaryButton(stringResource(R.string.gen_continue_without_images), onContinueWithoutImages)
        }
    }
}

private fun imageStatus(ui: OnboardingUi, kind: String): String = when {
    ui.images[kind]?.status == "done" -> "done"
    ui.images[kind]?.status == "failed" || (ui.draft.imagesError && ui.images[kind] == null) -> "failed"
    ui.draft.plan == PlanState.Pending && ui.images[kind] == null -> "pending"
    else -> "running"
}

@Composable
private fun GenRow(label: Int, sub: Int, status: String) {
    val c = LocalBridgeColors.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            when (status) {
                "running" -> CircularProgressIndicator(Modifier.size(20.dp), color = c.acc, trackColor = c.accLine, strokeWidth = 2.dp)
                "done" -> Icon(painterResource(R.drawable.ic_check_circle), null, tint = c.ok, modifier = Modifier.size(24.dp))
                "failed" -> Icon(painterResource(R.drawable.ic_error), null, tint = c.bad, modifier = Modifier.size(24.dp))
                else -> Box(Modifier.size(18.dp).border(2.dp, c.line, CircleShape))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(label), style = BType.body(15, FontWeight.Medium), color = c.ink)
            Text(stringResource(if (status == "failed") R.string.gen_row_failed else sub), style = BType.body(12), color = if (status == "failed") c.bad else c.mut)
        }
    }
}

/** The framed "scanner" above the generation rows; the scan line stops when motion is reduced. */
@Composable
private fun Scanner(failed: Boolean, done: Boolean, noPhoto: Boolean) {
    val c = LocalBridgeColors.current
    val reduce = LocalReduceMotion.current
    val border = when { failed -> c.bad; done -> c.ok; else -> c.ink }
    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
        BoxWithConstraints(Modifier.size(width = 178.dp, height = 216.dp).clip(RoundedCornerShape(24.dp)).background(c.s0).border(1.5.dp, border, RoundedCornerShape(24.dp))) {
            if (!done && !failed) {
                val y = if (reduce) 0f else rememberInfiniteTransition(label = "scan").animateFloat(0f, 1f, infiniteRepeatable(tween(2100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "y").value
                Box(Modifier.fillMaxWidth().height(2.dp).offset(y = 10.dp + (maxHeight - 24.dp) * y).background(c.prog))
            }
            Text(stringResource(if (noPhoto) R.string.gen_scan_concept else R.string.gen_scan_photo), style = BType.label(10f, 0.1f), color = c.mut,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp))
        }
    }
}

/** The goal was too vague or not something we can plan: ask instead of generating. */
@Composable
private fun Clarify(question: String, onEdit: () -> Unit) {
    val c = LocalBridgeColors.current
    StepPage {
        StepHeader(4, null)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.gen_clarify_title), style = BType.label(11f, 0.14f), color = c.mut)
        Text(question, style = BType.display(26, 1.15f), color = c.ink, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.weight(1f))
        PrimaryButton(stringResource(R.string.gen_edit_goal), onEdit)
    }
}

@Composable
fun ReviewStep(
    ui: OnboardingUi, onLabel: (String) -> Unit, onDeadline: (LocalDate) -> Unit, onTitle: (String, String) -> Unit,
    onDelete: (String) -> Unit, onAdd: () -> Unit, onStart: () -> Unit,
) {
    val c = LocalBridgeColors.current
    val d = ui.draft
    val tasks = d.tasks
    val total = ui.otherTasks + tasks.size
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 110.dp)) {
            Box(Modifier.fillMaxWidth().height(340.dp)) {
                GoalImage(ui.images["future"]?.url, d.goal, Modifier.matchParentSize())
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to c.bg)))
                Column(Modifier.align(Alignment.BottomStart).padding(start = 24.dp, end = 24.dp, bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(d.goal, style = BType.display(24, 1.2f), color = c.ink, modifier = Modifier.semantics { heading() })
                }
            }
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(stringResource(R.string.review_goal_name), style = BType.label(10f, 0.12f), color = c.mut)
                        InlineField(d.label, onLabel, stringResource(R.string.review_goal_name_hint), stringResource(R.string.review_goal_name), Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .background(c.s1, RoundedCornerShape(12.dp)).border(1.dp, if (ui.labelError != null) c.bad else c.line, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp))
                    }
                    DateField(stringResource(R.string.target_date), ui.deadline, ui.today, Deadlines.MIN_DAYS_NEW, onDeadline, Modifier.weight(1f), compact = true)
                }
                ui.labelError?.let { Text(it.resolve(), style = BType.body(13), color = c.bad) }
                if (ui.deadlineProblem() != null) DeadlineMessage(ui.deadline, ui.today, Deadlines.MIN_DAYS_NEW)
                d.feasibility?.let { FeasibilityCard(it, ui.deadline, compact = true) }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Text(stringResource(R.string.review_plan_title), style = BType.display(20), color = c.ink, modifier = Modifier.semantics { heading() })
                    Text(pluralStringResource(R.plurals.review_task_count, tasks.size, tasks.size).uppercase(), style = BType.label(11f, 0.12f), color = c.mut)
                }
                Text(stringResource(R.string.review_plan_sub), style = BType.body(14, lineHeight = 1.45f), color = c.mut)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tasks.forEachIndexed { i, t ->
                        Row(Modifier.fillMaxWidth().background(c.s1, RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("%02d".format(i + 1), style = BType.label(11f, 0f), color = c.mut, modifier = Modifier.width(20.dp))
                            InlineField(t.title, { onTitle(t.key, it) }, stringResource(R.string.review_task_hint), stringResource(R.string.review_task_a11y), Modifier.weight(1f).heightIn(min = 48.dp))
                            Box(Modifier.size(44.dp).clickable(role = Role.Button) { onDelete(t.key) }, contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.review_delete_task), tint = c.mut, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
                if (tasks.isEmpty()) Text(stringResource(R.string.review_empty), style = BType.body(13), color = c.warn,
                    modifier = Modifier.fillMaxWidth().background(c.warnTint, RoundedCornerShape(14.dp)).border(1.dp, c.warn, RoundedCornerShape(14.dp)).padding(14.dp))
                if (d.adding && total > 9) WarnNote(stringResource(R.string.review_heavy, total, ui.otherGoals + 1), R.drawable.ic_warning)
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).border(1.dp, c.line, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onAdd),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    Icon(painterResource(R.drawable.ic_add), null, tint = c.ink2, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.review_add_task), style = BType.body(14, FontWeight.Medium), color = c.ink2)
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(c.bg).padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp)) {
            PrimaryButton(stringResource(if (d.adding) R.string.review_add_goal else R.string.review_start), onStart, enabled = ui.canStart)
        }
    }
}

@Composable
private fun InlineField(value: String, onChange: (String) -> Unit, placeholder: String, a11y: String, modifier: Modifier) {
    val c = LocalBridgeColors.current
    LiveTextField(value, onChange, singleLine = true, textStyle = BType.body(15, FontWeight.Medium).copy(color = c.ink), cursorBrush = SolidColor(c.ink),
        modifier = modifier.semantics { contentDescription = a11y },
        decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) { if (value.isEmpty()) Text(placeholder, style = BType.body(15, FontWeight.Medium), color = c.mut2); inner() } })
}

/** Reality Check verdict. A suggested date is only ever offered; [onUseDate] lets the user take it. */
@Composable
fun FeasibilityCard(f: Feasibility, deadline: LocalDate?, compact: Boolean = false, onUseDate: ((LocalDate) -> Unit)? = null, note: String? = null) {
    val c = LocalBridgeColors.current
    val (tint, fg, label) = when (f.verdict) {
        "realistic" -> Triple(c.okTint, c.ok, R.string.verdict_realistic)
        "unrealistic" -> Triple(c.badTint, c.bad, R.string.verdict_unrealistic)
        else -> Triple(c.warnTint, c.warn, R.string.verdict_stretch)
    }
    Column(Modifier.fillMaxWidth().background(tint, RoundedCornerShape(16.dp)).border(1.dp, fg.copy(alpha = .5f), RoundedCornerShape(16.dp)).padding(if (compact) 14.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!compact && deadline != null) Text(stringResource(R.string.reality_verdict_kicker, shortDate(deadline).uppercase()), style = BType.label(10f, 0.14f), color = c.mut)
        Text(stringResource(label), style = BType.label(11f, 0.14f), color = fg)
        Text(f.reason, style = BType.body(14, lineHeight = 1.5f), color = c.ink2)
        val suggested = f.suggestedDeadline?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.takeIf { it != deadline }
        if (suggested != null && onUseDate != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.heightIn(min = 40.dp).background(c.s1, RoundedCornerShape(20.dp)).border(1.dp, c.line, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button) { onUseDate(suggested) }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.verdict_use_date, shortDate(suggested)), style = BType.body(13, FontWeight.SemiBold), color = c.ink)
                }
                Text(stringResource(R.string.verdict_keep_date), style = BType.body(12), color = c.mut, modifier = Modifier.weight(1f))
            }
        }
        if (note != null) Text(note, style = BType.body(12), color = c.mut)
    }
}
