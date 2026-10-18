package app.thebridge.feature.today

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Today tab: wires [TodayScreen] and its sheets to the ViewModel. */
@Composable
fun TodayRoute(onAddGoal: () -> Unit, onOpenJournal: (goalId: String) -> Unit, onOpenCommunity: (goalId: String) -> Unit = {}, vm: TodayViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val finished by vm.finished.collectAsStateWithLifecycle()
    val hasPlus by vm.hasPlus.collectAsStateWithLifecycle(false)
    val ctx = LocalContext.current
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }        // "task:<id>" | "add" | "goal:<id>" | "limit"
    var notificationsOn by rememberSaveable { mutableStateOf(true) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { notificationsOn = NotificationManagerCompat.from(ctx).areNotificationsEnabled() } }

    // Android 13+: ask for notifications once the first plan exists (the moment reminders make sense), never again automatically.
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notificationsOn = it }
    val store = vm.store
    LaunchedEffect(ui.goals.isNotEmpty()) {
        if (ui.goals.isEmpty() || Build.VERSION.SDK_INT < 33 || notificationsOn) return@LaunchedEffect
        if (store.data.first()[ASKED] == true) return@LaunchedEffect
        store.edit { it[ASKED] = true }
        ask.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val addGoal = { if (ui.atLimit && !hasPlus) sheet = "limit" else onAddGoal() }
    TodayScreen(ui, TodayActions(
        openGoal = vm::openGoal, showView = vm::showView, toggle = { id, done -> vm.toggle(id, done) }, addGoal = addGoal,
        openTask = { sheet = "task:${it.task.id}" }, newTask = { sheet = "add" }, editGoal = { sheet = "goal:${it.goal.id}" },
        openJournal = onOpenJournal, onNote = vm::onRealityNote, useDate = { id, d -> vm.useSuggestedDate(id, d) }, recheck = vm::recheck,
        retryImages = { vm.retryImages(it) }, openCommunity = onOpenCommunity, complete = vm::complete, archive = { sheet = "archive:$it" },
        openNotificationSettings = if (notificationsOn || ui.goals.isEmpty()) null else { {
            ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        } },
    ))

    val open = ui.open
    val s = sheet
    when {
        s == null -> Unit
        s == "limit" -> LimitSheet(onManage = { sheet = null; vm.openGoal(null) }, onUpgrade = { sheet = "plus" }, onDismiss = { sheet = null })
        s == "plus" -> app.thebridge.feature.paywall.PaywallSheet(app.thebridge.data.Product.Plus, onDismiss = { sheet = null })
        s == "add" && open != null -> TaskSheet(null, onSave = { t, n, p, w -> vm.addTask(open.goal.id, t, n, p, w); sheet = null }, onToggle = {}, onDelete = {}, onDismiss = { sheet = null })
        s.startsWith("task:") -> {
            val t = ui.goals.flatMap { it.tasks }.find { it.task.id == s.removePrefix("task:") }
            if (t == null) sheet = null
            else TaskSheet(t, onSave = { title, n, p, w -> vm.updateTask(t.task.id, title, n, p, w); sheet = null }, onToggle = { vm.toggle(t.task.id, it); sheet = null },
                onDelete = { vm.deleteTask(t.task.id); sheet = null }, onDismiss = { sheet = null })
        }
        s.startsWith("goal:") -> {
            val g = ui.goals.find { it.goal.id == s.removePrefix("goal:") }
            if (g == null) sheet = null
            else EditGoalSheet(g, canArchive = true, onSave = { label, d -> vm.saveGoal(g.goal.id, label, d); sheet = null }, onArchive = { vm.archive(g.goal.id); sheet = null }, onDismiss = { sheet = null },
                onComplete = { vm.complete(g.goal.id); sheet = null })
        }
        s.startsWith("archive:") -> ArchiveGoalSheet(onArchive = { vm.archive(s.removePrefix("archive:")); sheet = null }, onDismiss = { sheet = null })
        else -> sheet = null
    }

    ui.celebrate?.let { id ->
        val g = ui.goals.find { it.goal.id == id } ?: return@let
        val next = ui.goals.firstOrNull { it.goal.id != id && it.progress.tasksToday > it.progress.doneToday }
        CelebrateScreen(g, g.progress.streak, next, onNext = { vm.closeCelebrate(); next?.let { vm.openGoal(it.goal.id) } }, onJournal = { vm.closeCelebrate(); onOpenJournal(id) }, onClose = vm::closeCelebrate)
    }
    finished?.let { g -> GoalCrossedScreen(g, onNext = { vm.closeFinished(); addGoal() }, onClose = vm::closeFinished) }
}

private val ASKED = booleanPreferencesKey("notifications_asked")
