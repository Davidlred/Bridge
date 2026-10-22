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
import app.thebridge.data.JournalItem
import app.thebridge.data.local.ChatMessageEntity
import app.thebridge.data.local.JournalEntity
import app.thebridge.data.remote.SuggestionDto
import app.thebridge.feature.coach.ChatItem
import app.thebridge.feature.coach.CoachActions
import app.thebridge.feature.coach.CoachScreen
import app.thebridge.feature.coach.CoachUi
import app.thebridge.feature.coach.StudyModeScreen
import app.thebridge.feature.journal.JournalActions
import app.thebridge.feature.journal.JournalScreen
import app.thebridge.feature.journal.JournalTab
import app.thebridge.feature.journal.JournalUi
import app.thebridge.feature.journal.SaveState
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
import app.thebridge.feature.today.GoalCrossedContent
import app.thebridge.feature.today.ChooseGoalsContent
import app.thebridge.feature.today.GoalView
import app.thebridge.feature.today.TodayActions
import app.thebridge.feature.today.TodayScreen
import app.thebridge.feature.today.TodayUi
import app.thebridge.feature.shell.UpgradeScreen
import app.thebridge.ui.components.UiText
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import app.thebridge.data.local.AllianceMessageEntity
import app.thebridge.data.remote.AllianceDto
import app.thebridge.data.remote.AllianceFeedDto
import app.thebridge.data.remote.CommunityDto
import app.thebridge.data.remote.DoneCount
import app.thebridge.data.remote.InvitePreviewDto
import app.thebridge.data.remote.MemberDto
import app.thebridge.data.remote.PaceDto
import app.thebridge.data.remote.PostDto
import app.thebridge.data.remote.PreviewTaskDto
import app.thebridge.data.remote.ReactionCounts
import app.thebridge.data.remote.SharedPlanDto
import app.thebridge.feature.community.AllianceChatScreen
import app.thebridge.feature.community.AllianceChatUi
import app.thebridge.feature.community.ChatActions
import app.thebridge.feature.community.CommunityActions
import app.thebridge.feature.community.CommunityScreen
import app.thebridge.feature.community.CommunitySheet
import app.thebridge.feature.community.CommunityTab
import app.thebridge.feature.community.CommunityUi
import app.thebridge.feature.community.CreateAllianceContent
import app.thebridge.feature.community.JoinAllianceContent
import app.thebridge.data.remote.BadgeDto
import app.thebridge.data.remote.BadgesDto
import app.thebridge.data.remote.BoardDto
import app.thebridge.data.remote.BoardRowDto
import app.thebridge.data.remote.ClueDto
import app.thebridge.data.remote.GameDto
import app.thebridge.data.remote.GamePlayerDto
import app.thebridge.data.remote.GameStatusDto
import app.thebridge.data.remote.RatingDto
import app.thebridge.feature.community.HallOfFameScreen
import app.thebridge.feature.community.RankingsScreen
import app.thebridge.feature.game.GameActions
import app.thebridge.feature.game.GameScreen
import app.thebridge.feature.game.GameUi
import app.thebridge.data.Verification
import app.thebridge.data.local.DmMessageEntity
import app.thebridge.data.remote.DmContactDto
import app.thebridge.feature.dm.DmRow
import app.thebridge.feature.dm.InboxScreen
import app.thebridge.feature.dm.InboxUi
import app.thebridge.feature.dm.SafetyContent
import app.thebridge.feature.dm.ThreadActions
import app.thebridge.feature.dm.ThreadScreen
import app.thebridge.feature.dm.ThreadUi
import app.thebridge.data.Offer
import app.thebridge.data.Period
import app.thebridge.data.Product
import app.thebridge.feature.paywall.PaywallContent
import app.thebridge.feature.paywall.PaywallUi
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

    @Test fun splash() = snap("splash") { SplashScreen(animate = false) }
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
        task("t1", "Easy run, 5 km at conversational pace", "High", "7:00 AM") to true,
        task("t2", "Mobility, 10 min hip routine", "Medium", "After run") to true,
        task("t3", "In bed by 10:30 PM", "High", "10:30 PM") to false,
        task("t4", "Call Sam about Saturday long run", "Low", "Lunch", ai = false) to false,
    ), Feasibility("stretch", "Possible with four runs a week; your sleep is the bottleneck.", "2027-02-07"))
    private val spanish = goal("g2", "Spanish B1", 1, LocalDate.of(2026, 10, 4), LocalDate.of(2027, 6, 1), listOf(task("t5", "Flashcards, 15 min", "High", "Commute") to true))
    @Test fun todaySingle() = snap("today") { TodayScreen(TodayUi(true, "Sharon", listOf(marathon)), TodayActions(), LocalTime.of(9, 41), day) }
    @Test fun todayOverview() = snap("today-goals") { TodayScreen(TodayUi(true, "Sharon", listOf(marathon, spanish)), TodayActions(), LocalTime.of(9, 41), day) }
    // A goal with first steps: yesterday's step carried over, today's step, a habit; links on each.
    @Test fun todaySteps() = snap("today-steps") {
        val book = app.thebridge.data.remote.LinkDto("The Rust Programming Language", "https://doc.rust-lang.org/book/")
        val rust = marathon.copy(goal = marathon.goal.copy(label = "Rust CLI", statement = "Learn Rust well enough to build a command-line tool"), openSteps = listOf("s1", "s2"), tasks = listOf(
            PlanTask(TaskEntity("s1", "g1", "Install Rust with rustup and run cargo new hello", "Medium", origin = "ai", onDate = day.minusDays(1).toString()), false,
                listOf(app.thebridge.data.remote.LinkDto("Install Rust", "https://www.rust-lang.org/tools/install")), day.minusDays(1)),
            PlanTask(TaskEntity("s2", "g1", "Read chapters 1 and 2 of The Rust Programming Language", "Medium", origin = "ai", onDate = day.toString()), false, listOf(book)),
            PlanTask(task("t1", "Read The Rust Programming Language for 30 min", "High", "7:30 PM"), true, listOf(book))))
        TodayScreen(TodayUi(true, "Sharon", listOf(rust)), TodayActions(), LocalTime.of(9, 41), day)
    }
    @Test fun links() = snap("links") {
        androidx.compose.foundation.layout.Column(Modifier.background(LocalBridgeColors.current.s1).padding(24.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            app.thebridge.feature.today.LinkRow(app.thebridge.data.remote.LinkDto("The Rust Programming Language", "https://doc.rust-lang.org/book/"))
            app.thebridge.feature.today.LinkRow(app.thebridge.data.remote.LinkDto("Ownership and borrowing explained", "https://www.youtube.com/results?search_query=rust%20ownership"))
            app.thebridge.feature.today.LinkRow(app.thebridge.data.remote.LinkDto("Rust by Example", "https://www.google.com/search?q=Rust%20by%20Example"))
        }
    }
    @Test fun todayEmpty() = snap("today-empty") { TodayScreen(TodayUi(true, "Sharon"), TodayActions(), LocalTime.of(9, 41), day) }
    private val overdue = goal("g1", "Half marathon", 0, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 10, 11), marathon.tasks.map { it.task to false })
    private val away = marathon.copy(tasks = marathon.tasks.map { it.copy(done = false) },
        progress = Progress.of(LocalDate.of(2026, 9, 20), LocalDate.of(2027, 1, 10), day, marathon.tasks.associate { it.task.id to LocalDate.of(2026, 9, 20) }, mapOf("t1" to setOf(LocalDate.of(2026, 10, 8)))))
    @Test fun todayRecap() = snap("today-recap") { TodayScreen(TodayUi(true, "Sharon", listOf(overdue)), TodayActions(), LocalTime.of(9, 41), day) }
    @Test fun todayAway() = snap("today-away") { TodayScreen(TodayUi(true, "Sharon", listOf(away)), TodayActions(), LocalTime.of(9, 41), day) }
    private val pausedGoal = spanish.goal.copy(id = "g3", label = "Save £2k", statement = "Save £2,000 for a deposit by June.", status = "paused")
    @Test fun todayPaused() = snap("today-paused") { TodayScreen(TodayUi(true, "Sharon", listOf(marathon, spanish), paused = listOf(pausedGoal)), TodayActions(), LocalTime.of(9, 41), day) }
    @Test fun chooseGoals() = snap("choose-goals") {
        Box(Modifier.background(LocalBridgeColors.current.s1).padding(24.dp)) { ChooseGoalsContent(listOf(marathon.goal, spanish.goal, pausedGoal), setOf("g1", "g2"), 3, {}, {}) }
    }
    @Test fun goalCrossed() =snap("goal-crossed") { GoalCrossedContent(overdue, {}, {}) }
    @Test fun realityCheck() =snap("reality") { TodayScreen(TodayUi(true, "Sharon", listOf(marathon), view = GoalView.Reality), TodayActions(), LocalTime.of(9, 41), day) }

    private fun ob(d: OnboardingDraft) = OnboardingUi(d, emptyMap(), 0, 0, fixedToday = LocalDate.of(2026, 10, 13))
    @Test fun obGoal() = snap("ob-goal") { GoalStep(ob(OnboardingDraft(goalId = "g", goal = "Get much fitter overall", deadline = "2027-02-09")), null, {}, {}, {}, {}) }
    @Test fun obRoutine() = snap("ob-routine") { RoutineStep(ob(OnboardingDraft(goalId = "g", step = Step.Routine, routine = "Up at 7:30, phone in bed until 8.")), {}, {}, {}, {}) }
    @Test fun obPhoto() = snap("ob-photo") { PhotoStep(ob(OnboardingDraft(goalId = "g", step = Step.Photo)), {}, {}, {}, {}, {}) }
    @Test fun obPhotoFailed() = snap("ob-photo-failed") { PhotoStep(ob(OnboardingDraft(goalId = "g", step = Step.Photo, photo = PhotoState.Failed)), {}, {}, {}, {}, {}) }
    @Test fun obGenerating() = snap("ob-generating") {
        GeneratingStep(OnboardingUi(OnboardingDraft(goalId = "g", step = Step.Generating, plan = PlanState.Done, photo = PhotoState.Done, genStartedAt = 1),
            mapOf("future" to GoalImageEntity("g", "future", "running", null, true), "reality" to GoalImageEntity("g", "reality", "running", null, true)), 0, 0), {}, {}, {}, {})
    }
    @Test fun obGenerationFailed() = snap("ob-generation-failed") { GeneratingStep(ob(OnboardingDraft(goalId = "g", step = Step.Generating, plan = PlanState.Failed, imagesError = true)), {}, {}, {}, {}) }
    // --- Milestone 3 ---
    private fun msg(id: String, role: String, text: String, status: String = "done", suggestion: SuggestionDto? = null, state: String? = null) =
        ChatItem(ChatMessageEntity(id, "c1", role, text, status, null, state), suggestion, false)
    private val chat = listOf(
        msg("m1", "coach", "Morning, Sharon. Your easy runs are 12 s/km faster than in week one. Today's priority is sleep."),
        msg("m2", "user", "Work's been heavy. Any quick wins?"),
        msg("m3", "coach", "Move breakfast prep to the evening. It frees 15 minutes and protects your 10:30 bedtime.",
            suggestion = SuggestionDto("Prep tomorrow's breakfast before 9 PM", "Frees 15 minutes and protects your bedtime.", "Medium", "8:30 PM"), state = "pending"),
        msg("m4", "user", "And if I miss a run?", status = "failed"),
    )
    @Test fun coachChat() = snap("coach") { CoachScreen(CoachUi(loaded = true, goals = listOf(marathon), focus = marathon, items = chat), CoachActions()) }
    @Test fun coachEmpty() = snap("coach-empty") { CoachScreen(CoachUi(loaded = true, goals = listOf(marathon), focus = marathon), CoachActions()) }
    @Test fun coachThinking() = snap("coach-thinking") { CoachScreen(CoachUi(loaded = true, goals = listOf(marathon, spanish), focus = marathon, items = chat.take(2), awaiting = true), CoachActions()) }
    @Test fun studyMode() = snap("study") {
        StudyModeScreen(CoachUi(loaded = true, goals = listOf(marathon), focus = marathon, items = chat, studySeconds = 754, studyNotes = "Hill repeats on Thursday, keep the long run easy.", studyReply = "m3"),
            {}, {}, {}, {}, {}, {}, {}, {}, {})
    }
    private val entry = JournalEntity("j1", "g1", "2026-10-13", 3, "Long run felt easier than last week. Legs got heavy around km 9 but I held pace.")
    @Test fun journalToday() = snap("journal") { JournalScreen(JournalUi(goals = listOf(marathon), goalId = "g1", mood = 3, text = entry.body, save = SaveState.Saved, today = "2026-10-13"), JournalActions()) { 24 } }
    @Test fun journalEmpty() = snap("journal-empty") { JournalScreen(JournalUi(goals = listOf(marathon), goalId = "g1", today = "2026-10-13"), JournalActions()) { 24 } }
    @Test fun journalConflict() = snap("journal-conflict") {
        val item = JournalItem(entry, entry.copy(id = "j2", body = "Written on the tablet: legs heavy, held pace anyway.", conflictOf = "j1"))
        JournalScreen(JournalUi(goals = listOf(marathon, spanish), goalId = "g1", text = entry.body, today = "2026-10-13", conflict = item, history = listOf(item)), JournalActions(back = {})) { 24 }
    }
    @Test fun journalHistory() = snap("journal-history") {
        JournalScreen(JournalUi(tab = JournalTab.History, goals = listOf(marathon), goalId = "g1", today = "2026-10-14",
            history = listOf(JournalItem(entry, null), JournalItem(entry.copy(id = "j0", localDate = "2026-10-12", mood = 1, body = "Skipped mobility. Work ran late and I ate at 10."), null))), JournalActions()) { 24 }
    }
    @Test fun journalHistoryEmpty() = snap("journal-history-empty") { JournalScreen(JournalUi(tab = JournalTab.History, goals = listOf(marathon), goalId = "g1"), JournalActions()) { null } }

    @Test fun obReview() = snap("ob-review") {
        ReviewStep(ob(OnboardingDraft(goalId = "g", step = Step.Review, goal = "Run a half marathon in under 2 hours.", deadline = "2027-02-09", label = "Half marathon", plan = PlanState.Done,
            feasibility = Feasibility("realistic", "Seventeen weeks is enough with three runs a week.", null),
            tasks = listOf(DraftTask("a", "Easy run, 5 km", "High", ai = true), DraftTask("b", "Mobility, 10 min", ai = true), DraftTask("c", "In bed by 10:30 PM", ai = true)),
            steps = listOf(app.thebridge.feature.onboarding.DraftStep("s1", 1, "Get fitted for running shoes"), app.thebridge.feature.onboarding.DraftStep("s2", 2, "Do Couch to 5K week 1, run 1")))), {}, {}, { _, _ -> }, {}, {}, {}, {})
    }
    // --- Milestone 4 ---
    private val now = java.time.LocalDateTime.of(2026, 10, 13, 9, 41).atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
    private val hour = 3_600_000L
    private val posts = listOf(
        PostDto("p1", "u1", name = "Jonas", kind = "streak", value = 30, createdAt = now - 2 * hour, reactions = ReactionCounts(12, 4, 6)),
        PostDto("p2", "u2", name = "Priya", kind = "day_complete", createdAt = now - 4 * hour, reactions = ReactionCounts(8, 11, 2), reacted = listOf("keepGoing")),
        PostDto("p3", "me", mine = true, name = "Sharon", kind = "milestone", value = 25, createdAt = now - 26 * hour, reactions = ReactionCounts(5, 2, 7)),
    )
    private val members = listOf("Ms Okafor", "Jide", "Funmi", "Sharon", "Lola", "Bayo").mapIndexed { i, n -> MemberDto("u$i", n, if (i == 0) "owner" else "member", away = i == 4, me = n == "Sharon") }
    private val calc = AllianceDto("a1", "Calc Prep 12B", "shared", "CALC-2026", 15, "2026-09-28", "member", "g1", 9, members, 6,
        SharedPlanDto("Ms", "Score 75% or higher on the Grade 12 math final.", "2026-12-04", DoneCount(1, 3), DoneCount(9, 18)),
        listOf(AllianceFeedDto("Jide", "completed", "Work 10 past-paper questions", now - 3 * hour), AllianceFeedDto("Lola", "joined", null, now - 30 * hour)))
    private val dawn = AllianceDto("a2", "Dawn Runners", "own", "DAWN-4821", 15, "2026-09-01", "owner", "g1", 14, members.take(4), 4)
    private val locked = CommunityUi(goal = marathon, community = CommunityDto(unlocked = false, streak = 4), loading = false)
    private val open = CommunityUi(goal = marathon, community = CommunityDto(unlocked = true, streak = 9, posts = posts), loading = false)
    @Test fun communityLocked() = snap("community-locked") { CommunityScreen(locked, CommunityActions(), day, now) }
    @Test fun communityPreview() = snap("community-preview") { CommunityScreen(locked.copy(previewing = true), CommunityActions(), day, now) }
    @Test fun communityPreviewAlliance() = snap("community-preview-alliance") { CommunityScreen(locked.copy(previewing = true, tab = CommunityTab.Alliance), CommunityActions(), day, now) }
    @Test fun communityFeed() = snap("community-feed") { CommunityScreen(open, CommunityActions(), day, now) }
    @Test fun communityFeedError() = snap("community-feed-error") { CommunityScreen(open.copy(community = null, feedError = true), CommunityActions(), day, now) }
    @Test fun allianceNone() = snap("alliance-none") { CommunityScreen(open.copy(tab = CommunityTab.Alliance), CommunityActions(), day, now) }
    @Test fun allianceShared() = snap("alliance-shared") { CommunityScreen(open.copy(tab = CommunityTab.Alliance, alliances = listOf(calc, dawn)), CommunityActions(), day, now) }
    @Test fun allianceCreate() = snap("alliance-create") {
        Box(Modifier.padding(24.dp)) { CreateAllianceContent(CommunitySheet.Create("Dawn Runners", shared = true), marathon, {}, {}, {}) }
    }
    @Test fun allianceJoinPreview() = snap("alliance-join-preview") {
        Box(Modifier.padding(24.dp)) {
            JoinAllianceContent(CommunitySheet.Join("CALC-2026", preview = InvitePreviewDto("a1", "Calc Prep 12B", "shared", "Ms", 11, 15, 4, "Score 75% or higher on the Grade 12 math final.", "Math final", "2026-12-04",
                listOf(PreviewTaskDto("Work 10 past-paper questions", "High", "After school"), PreviewTaskDto("Review one section of the formula sheet", "Medium", "7:30 PM")), PaceDto(16, 68, 53))), {}, {}, {})
        }
    }
    @Test fun allianceJoinError() = snap("alliance-join-error") { Box(Modifier.padding(24.dp)) { JoinAllianceContent(CommunitySheet.Join("DAWN-48", error = UiText.Res(R.string.join_bad_format)), {}, {}, {}) } }
    @Test fun allianceChat() = snap("alliance-chat") {
        AllianceChatScreen(AllianceChatUi(calc, listOf(
            AllianceMessageEntity("c1", "a1", "s1", "u1", "Jide", "Did anyone get question 7? The integral looks wrong.", false, now - 50 * 60_000),
            AllianceMessageEntity("c2", "a1", "s2", "me", "Sharon", "It's by parts twice. Check the sign on the second step.", true, now - 40 * 60_000),
            AllianceMessageEntity("c3", "a1", "s3", "u0", "Ms", "Good. Tomorrow's past paper is up after school.", false, now - 20 * 60_000),
            AllianceMessageEntity("c4", "a1", null, null, null, "On my way to the library", true, now - 60_000, "queued"),
            AllianceMessageEntity("c5", "a1", null, null, null, "See you there", true, now, "failed"),
        ), "Same"), ChatActions(), now)
    }
    // --- Milestone 5 ---
    private val gamePlayers = listOf("me" to "Sharon", "u1" to "Jide", "u2" to "Funmi", "u3" to "Bayo", "u4" to "Lola").map { (id, n) -> GamePlayerDto(id, n, ready = id != "u4") }
    private fun game(phase: String, extra: GameDto.() -> GameDto = { this }) = GameDto("g1", "a1", ranked = true, phase = phase, endsAt = now + 9_000, players = gamePlayers,
        planks = listOf("cracked", "ok", "ok", "ok", "cracked", "ok", "cracked", "ok"), integrity = 63,
        clues = listOf(ClueDto("u1", "Jide repaired plank 2."), ClueDto("u2", "Plank 1 loosened. Funmi was nearby."))).extra()
    private fun gameUi(g: GameDto, role: String? = "builder") = GameUi(me = "me", game = g, role = role, revealed = true, now = now, mine = 2)
    @Test fun allianceGameAndRating() = snap("alliance-game") {
        CommunityScreen(open.copy(tab = CommunityTab.Alliance, alliances = listOf(dawn), games = mapOf("a2" to GameStatusDto(unlocked = false, eligible = 4, finished = 3, waiting = listOf("Bayo"))),
            ratings = mapOf("a2" to RatingDto(82, "silver", 4, 31, "2-5", 86, 78, 64))), CommunityActions(), day, now)
    }
    @Test fun gameLobby() = snap("game-lobby") { GameScreen(gameUi(game("lobby") { copy(planks = emptyList()) }, null), GameActions()) }
    @Test fun gameRole() = snap("game-role") { GameScreen(gameUi(game("role"), "drifter"), GameActions()) }
    @Test fun gameRound() = snap("game-round") { GameScreen(gameUi(game("round")), GameActions()) }
    @Test fun gameVote() = snap("game-vote") { GameScreen(gameUi(game("vote")).copy(choice = "u2"), GameActions()) }
    @Test fun gameResult() = snap("game-result") {
        GameScreen(gameUi(game("result") { copy(drifterId = "u2", ejectedId = "u2", buildersWon = true, tally = mapOf("u2" to 3, "u1" to 1), mvpId = "me",
            players = players.map { it.copy(role = if (it.userId == "u2") "drifter" else "builder", funPoints = if (it.userId == "u2") 6 else 12) }) }), GameActions())
    }
    @Test fun rankings() = snap("rankings") {
        RankingsScreen(BoardDto("fitness", "2-5", 4, 31, listOf(BoardRowDto(1, "Iron Mornings", 94, "gold", 5), BoardRowDto(2, "Quiet Hustle", 88, "gold", 4),
            BoardRowDto(3, "Page a Day", 84, "silver", 3), BoardRowDto(4, "Dawn Runners", 82, "silver", 4, mine = true), BoardRowDto(5, "Slow Savers", 71, "bronze", 5))), false, {}, {})
    }
    @Test fun hallOfFame() = snap("hall") {
        HallOfFameScreen(BadgesDto(true, listOf(BadgeDto("first", true), BadgeDto("s7", true), BadgeDto("s21", false, progress = 9, goal = 21), BadgeDto("arch", true),
            BadgeDto("s30", false, progress = 9, goal = 30), BadgeDto("week", false), BadgeDto("ref", false, progress = 12, goal = 30), BadgeDto("ally", true), BadgeDto("goal", false))), {}, {})
    }
    // --- Encrypted DMs ---
    private val dmMsgs = listOf(
        DmMessageEntity("d1", "u1", false, "Saw your 30-day streak. How do you keep the mornings going?", now - 50 * 60_000),
        DmMessageEntity("d2", "u1", true, "Clothes out the night before, and the alarm across the room.", now - 45 * 60_000),
        DmMessageEntity("d3", "u1", false, "Trying that tomorrow.", now - 30 * 60_000),
        DmMessageEntity("d4", "u1", true, "Tell me how it goes", now - 60_000, status = "queued"),
        DmMessageEntity("d5", "u1", true, "Good luck!", now, status = "failed"),
    )
    @Test fun dmInbox() = snap("dm-inbox") {
        InboxScreen(InboxUi(listOf(
            DmRow(DmContactDto("u1", "Jide", true), dmMsgs[2], unread = true, verified = true),
            DmRow(DmContactDto("u2", "Funmi", true), dmMsgs[1].copy(peerUserId = "u2", createdAt = now - 26 * hour), unread = false, verified = false),
            DmRow(DmContactDto("u3", "Bayo", false), null, unread = false, verified = false)), loaded = true), {}, {}, now)
    }
    @Test fun dmThread() = snap("dm-thread") { ThreadScreen(ThreadUi("Jide", dmMsgs, Verification.Verified), ThreadActions(), now) }
    @Test fun dmThreadKeyChanged() = snap("dm-thread-changed") { ThreadScreen(ThreadUi("Jide", dmMsgs.take(3), Verification.Changed, input = "Are you on a new phone?"), ThreadActions(), now) }
    @Test fun dmSafety() = snap("dm-safety") {
        Box(Modifier.padding(24.dp)) { SafetyContent("Jide", listOf(listOf("05211", "84720", "39021", "77310", "10442", "93017", "28150", "64093", "11827", "50306", "72291", "40968")), {}, {}) }
    }
    // --- Paywall (prices come from the store; these are examples) ---
    @Test fun paywallAlliancePlus() = snap("paywall-alliance") {
        Box(Modifier.padding(24.dp)) { PaywallContent(PaywallUi(Product.AlliancePlus, loading = false, offers = listOf(Offer("m", "$3.99", Period.Month, 7)), selected = "m"), {}, {}, {}, {}) }
    }
    @Test fun appearance() = snap("appearance") { Box(Modifier.background(LocalBridgeColors.current.s1).padding(24.dp)) { app.thebridge.feature.shell.AppearanceContent("system") {} } }
    @Test fun coachStyles() = snap("coach-styles") { Box(Modifier.background(LocalBridgeColors.current.s1).padding(24.dp)) { app.thebridge.feature.shell.CoachStyleContent("balanced", hasPlus = false) {} } }
    @Test fun paywallPlusUnavailable() = snap("paywall-plus-off") { Box(Modifier.padding(24.dp)) { PaywallContent(PaywallUi(Product.Plus, available = false, loading = false), {}, {}, {}, {}) } }
    @Test fun profile() = snap("profile") { ProfileScreen("Sharon", "sharon@example.com", LocalDate.of(2026, 10, 1), {}, {}, onHall = {}) }
    @Test fun offlineBanner() = snap("offline") { OfflineBanner(3) }
    @Test fun upgrade() = snap("upgrade") { UpgradeScreen {} }
}
