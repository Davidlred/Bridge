package app.thebridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.thebridge.feature.auth.AuthUiState
import app.thebridge.feature.auth.LogInPage
import app.thebridge.feature.auth.SignUpScreen
import app.thebridge.feature.auth.SplashScreen
import app.thebridge.feature.auth.WelcomeScreen
import app.thebridge.feature.shell.CoachScreen
import app.thebridge.feature.shell.JournalScreen
import app.thebridge.feature.shell.OfflineBanner
import app.thebridge.feature.shell.ProfileScreen
import app.thebridge.data.GoalState
import app.thebridge.data.PlanTask
import app.thebridge.data.local.GoalEntity
import app.thebridge.data.local.GoalImageEntity
import app.thebridge.data.local.TaskEntity
import app.thebridge.data.remote.Feasibility
import app.thebridge.domain.Progress
import app.thebridge.feature.onboarding.DraftTask
import app.thebridge.feature.onboarding.GeneratingStep
import app.thebridge.feature.onboarding.GoalStep
import app.thebridge.feature.onboarding.OnboardingDraft
import app.thebridge.feature.onboarding.OnboardingUi
import app.thebridge.feature.onboarding.PhotoState
import app.thebridge.feature.onboarding.PhotoStep
import app.thebridge.feature.onboarding.PlanState
import app.thebridge.feature.onboarding.ReviewStep
import app.thebridge.feature.onboarding.RoutineStep
import app.thebridge.feature.onboarding.Step
import app.thebridge.feature.today.GoalView
import app.thebridge.feature.today.TodayActions
import app.thebridge.feature.today.TodayScreen
import app.thebridge.feature.today.TodayUi
import app.thebridge.feature.shell.UpgradeScreen
import app.thebridge.ui.components.UiText
import app.thebridge.ui.theme.BridgeTheme
import app.thebridge.ui.theme.LocalBridgeColors
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** Every milestone-1 and milestone-2 screen in light, dark and font scale 2.0. Record: `./gradlew recordPaparazziDebug`; check: `verifyPaparazziDebug`. */
private object NoResults : androidx.activity.result.ActivityResultRegistryOwner {
    override val activityResultRegistry = object : androidx.activity.result.ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: androidx.activity.result.contract.ActivityResultContract<I, O>, input: I, options: androidx.core.app.ActivityOptionsCompat?) = Unit
    }
}

class ScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    private fun snap(name: String, content: @Composable () -> Unit) {
        for ((variant, theme, scale) in listOf(Triple("light", "light", 1f), Triple("dark", "dark", 1f), Triple("font200", "light", 2f))) {
            paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_5.copy(fontScale = scale))
            paparazzi.snapshot("$name-$variant") {
                // Screens that launch pickers need an activity-result owner; a no-op one is enough to draw them.
                androidx.compose.runtime.CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides NoResults) {
                    BridgeTheme(theme) { Box(Modifier.fillMaxSize().background(LocalBridgeColors.current.bg)) { content() } }
                }
            }
        }
    }

    private val filled = AuthUiState(name = "Sharon", email = "sharon@example.com", password = "correct horse")
    private val errors = AuthUiState(name = "S", email = "sharon@", nameError = UiText.Res(R.string.err_name), emailError = UiText.Res(R.string.err_email),
        passwordError = UiText.Res(R.string.err_password_short), formError = UiText.Res(R.string.err_signup_offline))

    @Test fun splash() = snap("splash") { SplashScreen() }
    @Test fun welcome() = snap("welcome") { WelcomeScreen({}, {}) }
    @Test fun signUp() = snap("signup") { SignUpScreen(filled, {}, {}, {}, {}, {}, {}, {}) }
    @Test fun signUpErrors() = snap("signup-errors") { SignUpScreen(errors, {}, {}, {}, {}, {}, {}, {}) }
    @Test fun logIn() = snap("login") { LogInPage(AuthUiState(email = "sharon@example.com"), {}, {}, {}, {}, {}, {}) }
    @Test fun logInWrongPassword() = snap("login-error") { LogInPage(filled.copy(passwordError = UiText.Res(R.string.err_credentials)), {}, {}, {}, {}, {}, {}) }
    @Test fun logInLocked() = snap("login-locked") { LogInPage(filled.copy(formError = UiText.Plural(R.plurals.err_too_many_attempts, 15)), {}, {}, {}, {}, {}, {}) }
    // --- Milestone 2 ---
    private val day = LocalDate.of(2026, 10, 13)
    private fun goal(id: String, label: String, colour: Int, start: LocalDate, deadline: LocalDate, tasks: List<Pair<TaskEntity, Boolean>>, feasibility: Feasibility? = null): GoalState {
        val created = tasks.associate { it.first.id to start }
        val done = tasks.associate { (t, d) -> t.id to ((1..6).map { day.minusDays(it.toLong()) } + if (d) listOf(day) else emptyList()).toSet() }
        return GoalState(GoalEntity(id, label, "Run a half marathon in under 2 hours.", start.toString(), deadline.toString(), colorIndex = colour, routine = "Up at 7:30, desk job 9 to 6, asleep by 1.", realityNote = "Because I'm done starting over every January."),
            tasks.map { PlanTask(it.first, it.second) }, Progress.of(start, deadline, day, created, done), feasibility,
            mapOf("future" to GoalImageEntity(id, "future", "done", null, true), "reality" to GoalImageEntity(id, "reality", "done", null, true)))
    }
    private fun task(id: String, title: String, priority: String, time: String?, ai: Boolean = true) = TaskEntity(id, "g1", title, priority, time, origin = if (ai) "ai" else "user", rationale = "Builds the aerobic base without fatigue.")
    private val marathon = goal("g1", "Half marathon", 0, LocalDate.of(2026, 9, 20), LocalDate.of(2027, 1, 10), listOf(
        task("t1", "Easy run — 5 km at conversational pace", "High", "7:00 AM") to true,
        task("t2", "Mobility — 10 min hip routine", "Medium", "After run") to true,
        task("t3", "In bed by 10:30 PM", "High", "10:30 PM") to false,
        task("t4", "Call Sam about Saturday long run", "Low", "Lunch", ai = false) to false,
    ), Feasibility("stretch", "Possible with four runs a week; your sleep is the bottleneck.", "2027-02-07"))
    private val spanish = goal("g2", "Spanish B1", 1, LocalDate.of(2026, 10, 4), LocalDate.of(2027, 6, 1), listOf(task("t5", "Flashcards — 15 min", "High", "Commute") to true))
    @Test fun todaySingle() = snap("today") { TodayScreen(TodayUi(true, "Sharon", listOf(marathon)), TodayActions(), LocalTime.of(9, 41), day) }
    @Test fun todayOverview() = snap("today-goals") { TodayScreen(TodayUi(true, "Sharon", listOf(marathon, spanish)), TodayActions(), LocalTime.of(9, 41), day) }
    @Test fun todayEmpty() = snap("today-empty") { TodayScreen(TodayUi(true, "Sharon"), TodayActions(), LocalTime.of(9, 41), day) }
    @Test fun realityCheck() = snap("reality") { TodayScreen(TodayUi(true, "Sharon", listOf(marathon), view = GoalView.Reality), TodayActions(), LocalTime.of(9, 41), day) }

    private fun ob(d: OnboardingDraft) = OnboardingUi(d, emptyMap(), 0, 0)
    @Test fun obGoal() = snap("ob-goal") { GoalStep(ob(OnboardingDraft(goalId = "g", goal = "Get much fitter overall", deadline = "2027-02-09")), null, {}, {}, {}, {}) }
    @Test fun obRoutine() = snap("ob-routine") { RoutineStep(ob(OnboardingDraft(goalId = "g", step = Step.Routine, routine = "Up at 7:30, phone in bed until 8.")), {}, {}, {}, {}) }
    @Test fun obPhoto() = snap("ob-photo") { PhotoStep(ob(OnboardingDraft(goalId = "g", step = Step.Photo)), {}, {}, {}, {}, {}) }
    @Test fun obPhotoFailed() = snap("ob-photo-failed") { PhotoStep(ob(OnboardingDraft(goalId = "g", step = Step.Photo, photo = PhotoState.Failed)), {}, {}, {}, {}, {}) }
    @Test fun obGenerating() = snap("ob-generating") {
        GeneratingStep(OnboardingUi(OnboardingDraft(goalId = "g", step = Step.Generating, plan = PlanState.Done, photo = PhotoState.Done, genStartedAt = 1),
            mapOf("future" to GoalImageEntity("g", "future", "running", null, true), "reality" to GoalImageEntity("g", "reality", "running", null, true)), 0, 0), {}, {}, {}, {})
    }
    @Test fun obGenerationFailed() = snap("ob-generation-failed") { GeneratingStep(ob(OnboardingDraft(goalId = "g", step = Step.Generating, plan = PlanState.Failed, imagesError = true)), {}, {}, {}, {}) }
    @Test fun obReview() = snap("ob-review") {
        ReviewStep(ob(OnboardingDraft(goalId = "g", step = Step.Review, goal = "Run a half marathon in under 2 hours.", deadline = "2027-02-09", label = "Half marathon", plan = PlanState.Done,
            feasibility = Feasibility("realistic", "Seventeen weeks is enough with three runs a week.", null),
            tasks = listOf(DraftTask("a", "Easy run — 5 km", "High", ai = true), DraftTask("b", "Mobility — 10 min", ai = true), DraftTask("c", "In bed by 10:30 PM", ai = true)))), {}, {}, { _, _ -> }, {}, {}, {})
    }
    @Test fun coach() = snap("coach") { CoachScreen("Arc") }
    @Test fun journal() = snap("journal") { JournalScreen() }
    @Test fun profile() = snap("profile") { ProfileScreen("Sharon", "sharon@example.com", LocalDate.of(2026, 10, 1), {}, {}) }
    @Test fun offlineBanner() = snap("offline") { OfflineBanner(3) }
    @Test fun upgrade() = snap("upgrade") { UpgradeScreen {} }
}
