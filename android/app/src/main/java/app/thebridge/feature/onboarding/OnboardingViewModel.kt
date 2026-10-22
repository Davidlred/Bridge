package app.thebridge.feature.onboarding

import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.thebridge.R
import app.thebridge.data.AiRepository
import app.thebridge.data.GoalRepository
import app.thebridge.data.NewGoal
import app.thebridge.data.NewTask
import app.thebridge.data.local.BridgeDb
import app.thebridge.data.local.GoalImageEntity
import app.thebridge.data.local.LocalDay
import app.thebridge.data.remote.ApiException
import app.thebridge.data.remote.Feasibility
import app.thebridge.data.remote.OfflineException
import app.thebridge.data.session.SessionManager
import app.thebridge.domain.Deadlines
import app.thebridge.domain.GoalText
import app.thebridge.ui.Toaster
import app.thebridge.ui.components.ToastKind
import app.thebridge.ui.components.UiText
import app.thebridge.util.UuidV7
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.LocalDate
import javax.inject.Inject

enum class Step { Goal, Routine, Photo, Generating, Review }
enum class PlanState { Pending, Running, Done, Failed, Clarify, Quota }
enum class PhotoState { None, Uploading, Done, Failed }

@Serializable
data class DraftTask(val key: String = UuidV7.next(), val title: String, val priority: String = "Medium", val timeOfDay: String? = null, val minutes: Int? = null, val rationale: String? = null, val ai: Boolean = false,
                     val resources: List<app.thebridge.data.remote.LinkDto> = emptyList())

/** A one-time first-week step from the plan. */
@Serializable
data class DraftStep(val key: String = UuidV7.next(), val day: Int, val title: String, val minutes: Int? = null, val rationale: String? = null, val resources: List<app.thebridge.data.remote.LinkDto> = emptyList())

/** Everything typed during onboarding. Saved on every change so a killed app resumes at the same step with the same inputs. */
@Serializable
data class OnboardingDraft(
    val goalId: String = UuidV7.next(),
    val adding: Boolean = false,
    val step: Step = Step.Goal,
    val goal: String = "",
    val deadline: String? = null,
    val routine: String = "",
    val routineFromSetup: Boolean = false,
    val photo: PhotoState = PhotoState.None,
    val photoUrl: String? = null,
    val plan: PlanState = PlanState.Pending,
    val clarification: String? = null,
    val label: String = "",
    val tasks: List<DraftTask> = emptyList(),
    val steps: List<DraftStep> = emptyList(),
    val feasibility: Feasibility? = null,
    val category: String? = null,
    val genStartedAt: Long = 0,
    /** The image request itself failed (offline, daily limit), so no image rows exist to report it. */
    val imagesError: Boolean = false,
)

data class OnboardingUi(
    val draft: OnboardingDraft,
    val images: Map<String, GoalImageEntity>,
    val otherTasks: Int,
    val otherGoals: Int,
    val busy: Boolean = false,
    val labelError: UiText? = null,
    /** Fixed day for previews and snapshot tests; null follows the clock. */
    val fixedToday: LocalDate? = null,
) {
    val today: LocalDate get() = fixedToday ?: LocalDate.parse(LocalDay.today())
    val deadline: LocalDate? get() = draft.deadline?.let(LocalDate::parse)
    fun deadlineProblem(min: Int = Deadlines.MIN_DAYS_NEW) = Deadlines.check(deadline, today, min)
    val goalReady: Boolean get() = draft.goal.trim().length >= GoalText.MIN_LENGTH && deadlineProblem() == null
    val routineReady: Boolean get() = draft.routine.trim().length >= 20
    val imagesRunning: Boolean get() = listOf("future", "reality").any { images[it]?.status == "running" || (!draft.imagesError && draft.plan != PlanState.Pending && images[it] == null) }
    val imagesFailed: Boolean get() = draft.imagesError || images.values.any { it.status == "failed" }
    val reviewTasks: List<DraftTask> get() = draft.tasks
    val canStart: Boolean get() = draft.tasks.any { it.title.isNotBlank() } && deadlineProblem() == null
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val store: DataStore<Preferences>,
    private val goals: GoalRepository,
    private val ai: AiRepository,
    private val session: SessionManager,
    private val toaster: Toaster,
    db: BridgeDb,
    private val analytics: app.thebridge.data.Analytics,
) : ViewModel() {
    private val draft = MutableStateFlow<OnboardingDraft?>(null)
    private val extra = MutableStateFlow(Triple(0, 0, false))     // other tasks, other goals, busy
    private val labelError = MutableStateFlow<UiText?>(null)
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private var genJob: Job? = null
    private var imageJob: Job? = null

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val images = draft.map { it?.goalId }.distinctUntilChanged().flatMapLatest { id -> if (id == null) flowOf(emptyList()) else db.goals().images(id) }

    val state: StateFlow<OnboardingUi?> = combine(draft, images, extra, labelError) { d, imgs, ex, err ->
        d?.let { OnboardingUi(it, imgs.associateBy(GoalImageEntity::kind), ex.first, ex.second, ex.third, err) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // Plan ready and both images settled: open the review after a beat, as in the prototype.
        viewModelScope.launch {
            state.collectLatest { s ->
                if (s == null || s.draft.step != Step.Generating || s.draft.plan != PlanState.Done || s.imagesRunning || s.imagesFailed) return@collectLatest
                delay(1_100)
                if (draft.value?.step == Step.Generating) edit { it.copy(step = Step.Review) }
            }
        }
    }

    /** Opens the flow: resumes a saved draft, or starts a new one ([adding] = another goal next to existing ones). */
    fun open(adding: Boolean) {
        viewModelScope.launch {
            val saved = store.data.first()[KEY]?.let { runCatching { json.decodeFromString(OnboardingDraft.serializer(), it) }.getOrNull() }
            val others = goals.activeCount()
            val start = if (saved != null && saved.adding == adding) saved else OnboardingDraft(
                adding = adding,
                routine = if (adding) goals.lastRoutine().orEmpty() else "",
                routineFromSetup = adding && !goals.lastRoutine().isNullOrBlank(),
                photo = if (session.user.value?.photoUrl != null) PhotoState.Done else PhotoState.None,
                photoUrl = session.user.value?.photoUrl,
            )
            extra.value = Triple(otherTaskCount(), others, false)
            set(start)
            // A generation that was running when the app was killed picks up again.
            if (start.step == Step.Generating) generate()
        }
    }

    private suspend fun otherTaskCount(): Int = goals.activeGoals().first().sumOf { it.tasks.size }

    private fun set(d: OnboardingDraft) {
        draft.value = d
        viewModelScope.launch { store.edit { it[KEY] = json.encodeToString(OnboardingDraft.serializer(), d) } }
    }
    private fun edit(f: (OnboardingDraft) -> OnboardingDraft) { draft.value?.let { set(f(it)) } }

    // --- Step 1 · Goal ---
    fun onGoal(v: String) = edit { it.copy(goal = v.take(GoalText.MAX_LENGTH)) }
    fun useExampleGoal(text: String) = edit { it.copy(goal = text) }
    fun onDeadline(d: LocalDate) = edit { it.copy(deadline = d.toString()) }
    fun toRoutine() { if (state.value?.goalReady == true) { edit { it.copy(step = Step.Routine) }; analytics.track(app.thebridge.data.Event.OnboardingStep, "step" to "routine") } }

    // --- Step 2 · Routine ---
    fun onRoutine(v: String) = edit { it.copy(routine = v.take(1000)) }
    fun useExampleRoutine(text: String) = edit { it.copy(routine = text) }
    fun toPhoto() { if (state.value?.routineReady == true) { edit { it.copy(step = Step.Photo) }; analytics.track(app.thebridge.data.Event.OnboardingStep, "step" to "photo") } }

    // --- Step 3 · Photo (the screen shows the consent sheet before calling this) ---
    fun uploadPhoto(uri: Uri) {
        edit { it.copy(photo = PhotoState.Uploading) }
        viewModelScope.launch {
            try {
                val url = ai.uploadPhoto(uri)
                edit { it.copy(photo = PhotoState.Done, photoUrl = url) }
                session.refreshUser()
            } catch (e: Exception) {
                edit { it.copy(photo = PhotoState.Failed) }
            }
        }
    }
    fun removePhoto() {
        viewModelScope.launch {
            runCatching { ai.deletePhoto() }
            session.refreshUser()
            edit { it.copy(photo = PhotoState.None, photoUrl = null) }
        }
    }
    fun generate() {
        edit { it.copy(step = Step.Generating, plan = if (it.plan == PlanState.Done) PlanState.Done else PlanState.Running, genStartedAt = System.currentTimeMillis()) }
        startPlan(); startImages()
    }
    fun skipPhoto() = generate()

    // --- Step 4 · Generating ---
    private fun startPlan() {
        val d = draft.value ?: return
        if (d.plan == PlanState.Done || genJob?.isActive == true) return
        edit { it.copy(plan = PlanState.Running, clarification = null) }
        genJob = viewModelScope.launch {
            try {
                val started = System.currentTimeMillis()
                val plan = ai.plan(d.goal.trim(), d.deadline!!, LocalDay.today(), d.routine.trim(), extra.value.second)
                analytics.track(app.thebridge.data.Event.PlanGenerated, "latency_ms" to (System.currentTimeMillis() - started), "tasks" to plan.tasks.size, "clarify" to (plan.needsClarification != null))
                if (plan.needsClarification != null) { edit { it.copy(plan = PlanState.Clarify, clarification = plan.needsClarification) }; return@launch }
                edit {
                    it.copy(plan = PlanState.Done, label = it.label.ifBlank { plan.label.ifBlank { GoalText.autoLabel(it.goal) } }, feasibility = plan.feasibility, category = plan.category,
                        tasks = plan.tasks.map { t -> DraftTask(title = t.title, priority = t.priority, timeOfDay = t.timeOfDay, minutes = t.minutes, rationale = t.rationale, ai = true, resources = t.resources) },
                        steps = plan.steps.map { s -> DraftStep(day = s.day, title = s.title, minutes = s.minutes, rationale = s.rationale, resources = s.resources) })
                }
            } catch (e: ApiException) {
                edit { it.copy(plan = if (e.code == "ai_quota" || e.status == 429) PlanState.Quota else PlanState.Failed) }
            } catch (e: Exception) {
                edit { it.copy(plan = PlanState.Failed) }
            }
        }
    }

    private fun startImages(kinds: List<String> = listOf("future", "reality")) {
        val d = draft.value ?: return
        if (imageJob?.isActive == true) return
        edit { it.copy(imagesError = false) }
        imageJob = viewModelScope.launch {
            try {
                ai.startImages(d.goalId, d.goal.trim(), d.routine.trim(), kinds)
                ai.awaitImages(d.goalId)
            } catch (e: Exception) {
                // Offline or daily limit: no image rows to report it, so flag it for the retry button.
                edit { it.copy(imagesError = true) }
            }
        }
    }

    fun retry() {
        val s = state.value ?: return
        if (s.draft.plan == PlanState.Failed || s.draft.plan == PlanState.Quota) startPlan()
        val failed = listOf("future", "reality").filter { s.images[it]?.status == "failed" || (s.draft.imagesError && s.images[it] == null) }
        if (failed.isNotEmpty()) startImages(failed)
    }

    /** Plan failed: start from three blank rows. */
    fun writeOwnTasks() {
        genJob?.cancel()
        edit { d -> d.copy(plan = PlanState.Done, label = d.label.ifBlank { GoalText.autoLabel(d.goal) }, tasks = List(3) { DraftTask(title = "") }, step = Step.Review) }
    }

    /** Images are optional: the plan can start without them (they keep generating and appear when ready). */
    fun continueWithoutImages() { if (draft.value?.plan == PlanState.Done) edit { it.copy(step = Step.Review) } }

    /** Back from Generating: cancel and return to step 1 with every input kept. */
    fun cancelGeneration() { genJob?.cancel(); edit { it.copy(step = Step.Goal, plan = if (it.plan == PlanState.Done) PlanState.Done else PlanState.Pending) } }

    // --- Review ---
    fun onLabel(v: String) { labelError.value = null; edit { it.copy(label = v.take(24)) } }
    fun onTaskTitle(key: String, v: String) = edit { d -> d.copy(tasks = d.tasks.map { if (it.key == key) it.copy(title = v.take(80)) else it }) }
    fun deleteTask(key: String) = edit { d -> d.copy(tasks = d.tasks.filterNot { it.key == key }) }
    fun addTask() = edit { d -> d.copy(tasks = d.tasks + DraftTask(title = "")) }
    fun deleteStep(key: String) = edit { d -> d.copy(steps = d.steps.filterNot { it.key == key }) }

    fun back() {
        when (draft.value?.step) {
            Step.Routine -> edit { it.copy(step = Step.Goal) }
            Step.Photo -> edit { it.copy(step = Step.Routine) }
            Step.Generating -> cancelGeneration()
            Step.Review -> edit { it.copy(step = Step.Goal) }
            else -> Unit
        }
    }

    /** Creates the goal and its tasks locally (synced later). Returns true when the flow is finished. */
    fun start(onDone: () -> Unit) {
        val s = state.value ?: return
        if (s.draft.label.trim().length < 2) { labelError.value = UiText.Res(R.string.review_name_err); return }
        if (!s.canStart) return
        val d = s.draft
        viewModelScope.launch {
            goals.createGoal(NewGoal(
                id = d.goalId, label = d.label, statement = d.goal, deadline = s.deadline!!, routine = d.routine.trim().ifBlank { null }, feasibility = d.feasibility, category = d.category,
                tasks = d.tasks.filter { it.title.isNotBlank() }.map { NewTask(it.title, it.priority, it.timeOfDay, it.minutes, it.rationale, ai = it.ai, resources = it.resources) },
                steps = d.steps.map { app.thebridge.data.NewStep(it.day, it.title, it.minutes, it.rationale, it.resources) },
            ))
            store.edit { it.remove(KEY) }
            if (d.adding) toaster.show(UiText.Res(R.string.toast_goal_added, listOf(d.label.trim(), s.otherGoals + 1)), ToastKind.Ok)
            else { session.onboardingDone(); toaster.show(UiText.Res(R.string.toast_day1), ToastKind.Ok) }
            onDone()
        }
    }

    /** Leaving "add a goal" without finishing: the draft is dropped. */
    fun discard() { viewModelScope.launch { store.edit { it.remove(KEY) } } }

    private companion object { val KEY = stringPreferencesKey("onboarding_draft") }
}
