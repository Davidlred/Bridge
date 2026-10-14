package app.thebridge.nav

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import app.thebridge.R
import app.thebridge.data.session.SessionPhase
import app.thebridge.feature.auth.AuthViewModel
import app.thebridge.feature.auth.LogInPage
import app.thebridge.feature.auth.LogInScreen
import app.thebridge.feature.auth.SignUpScreen
import app.thebridge.feature.auth.SplashScreen
import app.thebridge.feature.auth.WelcomeScreen
import app.thebridge.feature.coach.CoachRoute
import app.thebridge.feature.coach.StudyRoute
import app.thebridge.feature.journal.JournalRoute
import app.thebridge.feature.shell.DeleteAccountSheet
import app.thebridge.feature.shell.LogOutSheet
import app.thebridge.feature.shell.OfflineBanner
import app.thebridge.feature.shell.ProfileScreen
import app.thebridge.feature.shell.ShellViewModel
import app.thebridge.feature.today.TodayRoute
import app.thebridge.feature.onboarding.OnboardingFlow
import app.thebridge.feature.shell.UpgradeScreen
import app.thebridge.ui.components.BridgeSheet
import app.thebridge.ui.components.BridgeToast
import app.thebridge.ui.components.ToastData
import app.thebridge.ui.theme.BType
import app.thebridge.ui.theme.LocalBridgeColors
import app.thebridge.ui.theme.LocalReduceMotion
import app.thebridge.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

// Type-safe routes. Community lives inside a goal, so social routes carry a goalId.
@Serializable object TodayGraph
@Serializable object CoachGraph
@Serializable object JournalGraph
@Serializable object ProfileGraph
@Serializable object Today
@Serializable object Coach
@Serializable object Journal
@Serializable object Profile
@Serializable data class GoalDetail(val goalId: String)
@Serializable data class Community(val goalId: String)
@Serializable data class AllianceRoute(val goalId: String, val allianceId: String)
@Serializable data class AllianceChat(val allianceId: String)
@Serializable data class GameNight(val allianceId: String)
@Serializable data class InvitePreview(val code: String)
/** Full-screen focus timer with the coach strip. */
@Serializable object StudyMode
/** The journal pushed from Today ("Lock it in"), with a back link to Today. */
@Serializable data class JournalFromToday(val goalId: String)
@Serializable data class JournalEntry(val goalId: String, val localDate: String)
@Serializable object Messages
@Serializable data class DmThread(val contactId: String)
@Serializable object HallOfFame
/** Add a second or third goal: the onboarding steps, full screen. */
@Serializable object AddGoal

// Signed-out flow
@Serializable object Welcome
@Serializable object SignUp
@Serializable object LogIn

private data class TabItem(val graph: Any, val label: Int, val icon: Int)
private val tabs = listOf(
    TabItem(TodayGraph, R.string.tab_today, R.drawable.ic_today),
    TabItem(CoachGraph, R.string.tab_coach, R.drawable.ic_coach),
    TabItem(JournalGraph, R.string.tab_journal, R.drawable.ic_journal),
    TabItem(ProfileGraph, R.string.tab_profile, R.drawable.ic_profile),
)

private val PushEasing = CubicBezierEasing(.2f, .8f, .2f, 1f)

/** Session phase decides the whole tree: splash → signed-out flow → tabs (with the re-auth sheet on top when expired). */
@Composable
fun AppRoot(auth: AuthViewModel, shell: ShellViewModel = hiltViewModel()) {
    val phase by shell.phase.collectAsStateWithLifecycle()
    val online by shell.online.collectAsStateWithLifecycle()
    val queued by shell.queued.collectAsStateWithLifecycle()
    val c = LocalBridgeColors.current
    val ctx = LocalContext.current

    var toast by remember { mutableStateOf<ToastData?>(null) }
    LaunchedEffect(Unit) { shell.toasts.collect { toast = it } }
    LaunchedEffect(toast) { if (toast != null) { delay(Motion.toastHold); toast = null } }
    var mustUpgrade by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { shell.upgradeRequired.collect { mustUpgrade = true } }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        if (mustUpgrade) {
            UpgradeScreen(onOpenStore = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
            return@Box
        }
        // Ready and Expired share the tabs, so the key only changes between splash, auth flow, first-goal onboarding and tabs.
        val stage = when (phase) { SessionPhase.Launching -> 0; SessionPhase.SignedOut -> 1; SessionPhase.Onboarding -> 2; else -> 3 }
        AnimatedContent(stage, transitionSpec = { fadeIn(tween(Motion.fade)) togetherWith fadeOut(tween(Motion.fade)) }, label = "phase") { st ->
            Column(Modifier.fillMaxSize()) {
                if (st != 0 && !online) OfflineBanner(queued, Modifier.background(c.warnTint).windowInsetsPadding(WindowInsets.statusBars))
                when (st) {
                    0 -> SplashScreen()
                    1 -> AuthFlow(auth)
                    2 -> OnboardingFlow(adding = false, onExit = null, onFinished = {})
                    else -> MainShell(shell, offlineBannerShown = !online)
                }
            }
        }
        if (phase is SessionPhase.Expired) ReauthSheet(auth, queued)
        // Above the tab bar when it is showing, bottom-centre otherwise.
        BridgeToast(toast, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(start = 20.dp, end = 20.dp, bottom = if (phase is SessionPhase.Ready || phase is SessionPhase.Expired) 86.dp else 96.dp))
    }
}

@Composable
private fun AuthFlow(auth: AuthViewModel) {
    val nav = rememberNavController()
    val state by auth.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val reduce = LocalReduceMotion.current
    val go: (Any) -> Unit = { route -> auth.clearErrors(); nav.navigate(route) { popUpTo(Welcome); launchSingleTop = true } }

    NavHost(
        nav, startDestination = Welcome,
        enterTransition = { if (reduce) fadeIn(tween(Motion.fade)) else slideInHorizontally(tween(Motion.push, easing = PushEasing)) { it } },
        exitTransition = { fadeOut(tween(Motion.fade)) },
        popEnterTransition = { fadeIn(tween(Motion.fade)) },
        popExitTransition = { if (reduce) fadeOut(tween(Motion.fade)) else slideOutHorizontally(tween(Motion.push, easing = PushEasing)) { it } },
    ) {
        composable<Welcome> { WelcomeScreen(onGetStarted = { go(SignUp) }, onLogIn = { go(LogIn) }) }
        composable<SignUp> {
            SignUpScreen(state, auth::onName, auth::onEmail, auth::onPassword, onSubmit = auth::signUp, onGoogle = { auth.google(ctx) },
                onBack = { nav.popBackStack() }, onLogIn = { go(LogIn) })
        }
        composable<LogIn> {
            LogInPage(state, auth::onEmail, auth::onPassword, onSubmit = auth::logIn, onForgot = auth::forgot, onBack = { nav.popBackStack() }, onSignUp = { go(SignUp) })
        }
    }
}

/** Session expired: the app underneath keeps working on local data; nothing unsynced is dropped. */
@Composable
private fun ReauthSheet(auth: AuthViewModel, queued: Int) {
    val state by auth.state.collectAsStateWithLifecycle()
    val c = LocalBridgeColors.current
    BridgeSheet(onDismiss = {}, dismissible = false) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.session_expired_title), style = BType.body(14, FontWeight.SemiBold), color = c.warn)
            Text(stringResource(R.string.session_expired_body) + if (queued > 0) " " + pluralStringResource(R.plurals.session_expired_unsynced, queued, queued) else "", style = BType.body(13), color = c.mut)
            LogInScreen(state, auth::onEmail, auth::onPassword, onSubmit = auth::logIn, onForgot = auth::forgot, onBack = null, onSignUp = null, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun MainShell(shell: ShellViewModel, offlineBannerShown: Boolean) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val dest = entry?.destination
    val user by shell.user.collectAsStateWithLifecycle()
    val queued by shell.queued.collectAsStateWithLifecycle()
    val deleting by shell.deleting.collectAsStateWithLifecycle()
    val deleteError by shell.deleteError.collectAsStateWithLifecycle()
    val c = LocalBridgeColors.current
    val reduce = LocalReduceMotion.current
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    // Hide the bar on full-screen flows
    val fullScreen = dest?.hasRoute<StudyMode>() == true || dest?.hasRoute<GameNight>() == true || dest?.hasRoute<AddGoal>() == true
    val name = user?.displayName.orEmpty()
    val reminders by shell.reminders.collectAsStateWithLifecycle(app.thebridge.data.reminders.ReminderSettings())
    val coachReadsJournal by shell.coachReadsJournal.collectAsStateWithLifecycle(false)
    // One coach ViewModel for the tab and Study Mode, so they share the conversation.
    val coachVm = app.thebridge.feature.coach.rememberCoachViewModel()
    val toTab: (Any) -> Unit = { graph -> nav.navigate(graph) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true } }

    Column(Modifier.fillMaxSize()) {
        NavHost(
            nav, startDestination = TodayGraph,
            modifier = Modifier.weight(1f).then(if (offlineBannerShown) Modifier else Modifier.windowInsetsPadding(WindowInsets.statusBars)),
            enterTransition = { if (reduce) fadeIn(tween(Motion.fade)) else slideInHorizontally(tween(Motion.push, easing = PushEasing)) { it / 6 } + fadeIn(tween(Motion.fade)) },
            exitTransition = { fadeOut(tween(Motion.fade)) },
            popEnterTransition = { fadeIn(tween(Motion.fade)) },
            popExitTransition = { if (reduce) fadeOut(tween(Motion.fade)) else slideOutHorizontally(tween(Motion.push, easing = PushEasing)) { it / 6 } + fadeOut(tween(Motion.fade)) },
        ) {
            navigation<TodayGraph>(startDestination = Today) {
                composable<Today> { TodayRoute(onAddGoal = { nav.navigate(AddGoal) }, onOpenJournal = { goalId -> nav.navigate(JournalFromToday(goalId)) }) }
                composable<JournalFromToday> { JournalRoute(goalId = it.toRoute<JournalFromToday>().goalId, onBack = { nav.popBackStack() }) }
                composable<AddGoal> { OnboardingFlow(adding = true, onExit = { nav.popBackStack() }, onFinished = { nav.popBackStack() }) }
                composable<GoalDetail> { /* GoalDetailScreen(it.toRoute<GoalDetail>().goalId, …) */ }
                composable<Community> { /* CommunityScreen(goalId) — Feed / Alliance */ }
                composable<AllianceRoute> { }
                composable<AllianceChat> { }
                composable<GameNight> { }
                composable<InvitePreview>(deepLinks = listOf(navDeepLink<InvitePreview>(basePath = "https://thebridge.app/join"))) {
                    val code = it.toRoute<InvitePreview>().code.uppercase()
                    /* InvitePreviewScreen(code) */
                }
            }
            navigation<CoachGraph>(startDestination = Coach) {
                composable<Coach> { CoachRoute(coachVm, onStudy = { nav.navigate(StudyMode) }) }
                composable<StudyMode> { StudyRoute(coachVm, onExit = { nav.popBackStack() }) }
            }
            navigation<JournalGraph>(startDestination = Journal) {
                composable<Journal> { JournalRoute() }
                composable<JournalEntry> { }
            }
            navigation<ProfileGraph>(startDestination = Profile) {
                composable<Profile> {
                    ProfileScreen(
                        name, user?.email, user?.createdAt?.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() },
                        onLogOut = { if (queued > 0) sheet = "logout" else shell.logOut() },
                        onDelete = { shell.clearDeleteError(); sheet = "delete" },
                        reminders = reminders, onDaily = shell::cycleDaily, onRisk = shell::cycleRisk,
                        hasPhoto = user?.photoUrl != null, onRemovePhoto = shell::removePhoto,
                        coachName = user?.coachName ?: "Arc", onRenameCoach = shell::renameCoach, coachReadsJournal = coachReadsJournal, onCoachReadsJournal = shell::setCoachReadsJournal,
                    )
                }
                composable<HallOfFame> { }
                composable<Messages> { }
                composable<DmThread> { }
            }
        }
        if (!fullScreen) BottomBar(nav, dest)
    }

    when (sheet) {
        "delete" -> DeleteAccountSheet(deleting, deleteError, onConfirm = shell::deleteAccount, onDismiss = { sheet = null })
        "logout" -> LogOutSheet(queued, onConfirm = { sheet = null; shell.logOut() }, onDismiss = { sheet = null })
    }
}

/** Four tabs on `s0`; the active one gets a 56×30 pill behind its icon. */
@Composable
private fun BottomBar(nav: NavHostController, dest: androidx.navigation.NavDestination?) {
    val c = LocalBridgeColors.current
    Row(Modifier.fillMaxWidth().background(c.s0).windowInsetsPadding(WindowInsets.navigationBars).padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 8.dp)) {
        tabs.forEach { tab ->
            val selected = dest?.hierarchy?.any { it.hasRoute(tab.graph::class) } == true
            Column(
                Modifier.weight(1f).heightIn(min = 48.dp).selectable(selected = selected, role = Role.Tab, onClick = {
                    nav.navigate(tab.graph) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(Modifier.size(width = 56.dp, height = 30.dp).background(if (selected) c.pill else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                    Icon(painterResource(tab.icon), contentDescription = null, tint = if (selected) c.ink else c.mut, modifier = Modifier.size(22.dp))
                }
                Text(stringResource(tab.label), style = BType.body(11, FontWeight.Medium).copy(fontSize = 11.5.sp), color = if (selected) c.ink else c.mut, maxLines = 1)
            }
        }
    }
}

private val androidx.navigation.NavDestination.hierarchy get() = generateSequence(this) { it.parent }
