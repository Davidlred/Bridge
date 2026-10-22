package app.thebridge.feature.today

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.thebridge.R
import app.thebridge.data.AiRepository
import app.thebridge.data.GoalRepository
import app.thebridge.data.GoalState
import app.thebridge.data.local.GoalEntity
import app.thebridge.data.local.LocalDay
import app.thebridge.data.remote.OfflineException
import app.thebridge.data.session.SessionManager
import app.thebridge.ui.Toaster
import app.thebridge.ui.components.ToastKind
import app.thebridge.ui.components.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

enum class GoalView { Today, Reality }

data class TodayUi(
    val loaded: Boolean = false,
    val name: String = "",
    val goals: List<GoalState> = emptyList(),
    /** Goal shown in detail; null shows the overview cards (only when there are 2+ goals). */
    val openGoalId: String? = null,
    val view: GoalView = GoalView.Today,
    val celebrate: String? = null,
    val realityBusy: Boolean = false,
    val realityError: Boolean = false,
    /** Paused when Plus ended; the user chooses which goals stay active. */
    val paused: List<GoalEntity> = emptyList(),
    /** The goal whose next steps are being planned. */
    val stepsBusy: String? = null,
) {
    val streak: Int get() = goals.maxOfOrNull { it.progress.streak } ?: 0
    val open: GoalState? get() = goals.find { it.goal.id == openGoalId } ?: goals.singleOrNull()
    val showOverview: Boolean get() = goals.size > 1 && open == null
    val atLimit: Boolean get() = goals.size >= GoalRepository.FREE_GOAL_LIMIT
}

private data class ViewBits(val view: GoalView, val celebrate: String?, val reality: Pair<Boolean, Boolean>, val stepsBusy: String?)

@HiltViewModel
class TodayViewModel @Inject constructor(
    private val saved: SavedStateHandle,
    private val coach: app.thebridge.data.CoachRepository,
    private val repo: GoalRepository,
    private val ai: AiRepository,
    val store: DataStore<Preferences>,
    private val toaster: Toaster,
    session: SessionManager,
    private val social: app.thebridge.data.SocialRepository,
    billing: app.thebridge.data.BillingRepository,
    private val analytics: app.thebridge.data.Analytics,
) : ViewModel() {
    /** Plus lifts the three-goal limit (the server enforces it too). */
    val hasPlus = billing.active.map { "plus" in it }
    private val open = MutableStateFlow(saved.get<String>("open"))
    private val view = MutableStateFlow(GoalView.Today)
    private val celebrate = MutableStateFlow<String?>(null)
    private val reality = MutableStateFlow(false to false)   // busy, error
    private val stepsBusy = MutableStateFlow<String?>(null)

    val state: StateFlow<TodayUi> = combine(repo.activeGoals(), session.user.map { it?.displayName.orEmpty() }, open,
        combine(view, celebrate, reality, stepsBusy) { v, cel, r, sb -> ViewBits(v, cel, r, sb) }, repo.pausedGoals()) { goals, name, openId, b, paused ->
        TodayUi(true, name, goals, openId?.takeIf { id -> goals.any { it.goal.id == id } }, b.view, b.celebrate, b.reality.first, b.reality.second, paused, b.stepsBusy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUi())

    private val imagesRefreshedAt = mutableMapOf<String, Long>()

    fun openGoal(id: String?) {
        open.value = id; saved["open"] = id; view.value = GoalView.Today
        if (id != null) viewModelScope.launch { coach.setFocus(id) }
        if (id != null) refreshImages(listOf(id))
    }
    fun showView(v: GoalView) { view.value = v }

    /**
     * Signed image links last an hour, and after a new login or reinstall there are no image rows at all. Renewed for every
     * active goal when Today is shown or the app comes back, at most every 30 minutes per goal. (Only renewing on "open goal"
     * missed people with a single goal, who never open one: their images read "couldn't be made".)
     */
    fun refreshImages(goalIds: List<String>) {
        val now = System.currentTimeMillis()
        for (id in goalIds) {
            if (now - (imagesRefreshedAt[id] ?: 0L) < 30 * 60_000L) continue
            imagesRefreshedAt[id] = now
            viewModelScope.launch { ai.refreshImages(id) }
        }
    }

    fun toggle(taskId: String, done: Boolean) = viewModelScope.launch {
        repo.setDone(taskId, done)
        if (done) {
            analytics.track(app.thebridge.data.Event.TaskCompleted)
            launch { repo.goalOf(taskId)?.let { social.announce(it) } }      // share progress, if the user allows it
            checkCelebrate()
        }
    }

    /** All of a goal's tasks done for the first time today: celebrate once per goal per day. */
    private suspend fun checkCelebrate() {
        delay(650)                                                       // let the check animation land first
        val s = state.value
        val g = s.goals.firstOrNull { it.progress.allDone && it.goal.id == (s.open?.goal?.id ?: it.goal.id) } ?: return
        val key = "${g.goal.id}|${LocalDay.today()}"
        val shown = store.data.first()[CELEBRATED].orEmpty()
        if (key in shown) return
        store.edit { it[CELEBRATED] = (shown.filter { k -> k.substringAfter('|') >= LocalDate.parse(LocalDay.today()).minusDays(7).toString() } + key).toSet() }
        analytics.track(app.thebridge.data.Event.DayCompleted, "tasks" to g.progress.tasksToday, "streak" to g.progress.streak)
        celebrate.value = g.goal.id
    }
    fun closeCelebrate() { celebrate.value = null }

    fun addTask(goalId: String, title: String, notes: String, priority: String, time: String) = viewModelScope.launch {
        repo.addTask(goalId, title, notes, priority, time); toaster.show(UiText.Res(R.string.toast_task_added), ToastKind.Ok)
    }
    fun updateTask(id: String, title: String, notes: String, priority: String, time: String) = viewModelScope.launch {
        repo.updateTask(id, title, notes, priority, time); toaster.show(UiText.Res(R.string.toast_task_updated), ToastKind.Ok)
    }
    fun deleteTask(id: String) = viewModelScope.launch { repo.deleteTask(id); toaster.show(UiText.Res(R.string.toast_task_deleted)) }

    /** A new deadline re-runs the Reality Check (not the plan). The date itself is exactly what the user picked. */
    fun saveGoal(id: String, label: String, deadline: LocalDate) = viewModelScope.launch {
        val before = repo.activeGoals().first().find { it.goal.id == id }?.goal ?: return@launch
        repo.updateGoal(id, label = label, deadline = deadline)
        toaster.show(UiText.Res(R.string.toast_goal_updated), ToastKind.Ok)
        if (before.deadline != deadline.toString()) recheck(id)
    }

    fun useSuggestedDate(id: String, date: LocalDate) = viewModelScope.launch { repo.updateGoal(id, deadline = date); recheck(id) }

    private var realityJob: Job? = null
    fun recheck(id: String) {
        realityJob?.cancel()
        realityJob = viewModelScope.launch {
            val g = state.value.goals.find { it.goal.id == id } ?: repo.activeGoals().first().find { it.goal.id == id } ?: return@launch
            reality.value = true to false
            try {
                val f = ai.reality(g.goal.statement, g.goal.deadline, LocalDay.today(), g.goal.routine.orEmpty(), g.tasks.map { it.task.title })
                repo.updateGoal(id, feasibility = f)
                reality.value = false to false
            } catch (e: Exception) {
                reality.value = false to true                              // keep the last verdict; say it couldn't refresh
            }
        }
    }

    private var noteJob: Job? = null
    /** "Why I started" autosaves a moment after typing stops. */
    fun onRealityNote(id: String, text: String) {
        noteJob?.cancel()
        noteJob = viewModelScope.launch { delay(800); repo.updateGoal(id, realityNote = text.take(1000)) }
    }

    /** Plans the next week of steps from what is done and still open. */
    fun planSteps(g: GoalState) {
        if (stepsBusy.value != null) return
        stepsBusy.value = g.goal.id
        viewModelScope.launch {
            try {
                val steps = ai.nextSteps(g.goal.statement, g.goal.deadline, LocalDay.today(), g.goal.routine.orEmpty(), g.habits, g.doneSteps, g.openSteps)
                repo.addSteps(g.goal.id, steps)
                toaster.show(UiText.Res(R.string.toast_steps_added), ToastKind.Ok)
            } catch (e: OfflineException) {
                toaster.show(UiText.Res(R.string.error_offline), ToastKind.Warn)
            } catch (e: Exception) {
                toaster.show(UiText.Res(R.string.steps_failed), ToastKind.Warn)
            } finally { stepsBusy.value = null }
        }
    }

    fun retryImages(g: GoalState) = viewModelScope.launch {
        val failed = listOf("future", "reality").filter { g.images[it]?.status != "done" }
        try { ai.startImages(g.goal.id, g.goal.statement, g.goal.routine.orEmpty(), failed); ai.awaitImages(g.goal.id) }
        catch (e: OfflineException) { toaster.show(UiText.Res(R.string.error_offline), ToastKind.Warn) }
        catch (e: Exception) { toaster.show(UiText.Res(R.string.error_generic), ToastKind.Warn) }
    }

    fun archive(id: String) = viewModelScope.launch {
        repo.archiveGoal(id)
        if (open.value == id) openGoal(null)
        toaster.show(UiText.Res(R.string.toast_goal_archived))
    }

    /** The goal just marked complete, kept for the celebration after it leaves the active list. */
    private val _finished = MutableStateFlow<GoalState?>(null)
    val finished = _finished.asStateFlow()
    fun complete(id: String) = viewModelScope.launch {
        _finished.value = state.value.goals.find { it.goal.id == id } ?: return@launch
        repo.completeGoal(id)
        if (open.value == id) openGoal(null)
    }
    fun closeFinished() { _finished.value = null }

    fun chooseActive(keep: Set<String>) = viewModelScope.launch {
        repo.chooseActive(keep)
        if (open.value != null && open.value !in keep) openGoal(null)
    }

    private companion object { val CELEBRATED = stringSetPreferencesKey("celebrated") }
}
