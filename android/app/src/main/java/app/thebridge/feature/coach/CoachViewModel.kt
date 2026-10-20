package app.thebridge.feature.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.thebridge.R
import app.thebridge.data.CoachProblem
import app.thebridge.data.CoachRepository
import app.thebridge.data.GoalRepository
import app.thebridge.data.GoalState
import app.thebridge.data.local.ChatMessageEntity
import app.thebridge.data.remote.SuggestionDto
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatItem(val message: ChatMessageEntity, val suggestion: SuggestionDto?, val dividerBefore: Boolean)

data class CoachUi(
    /** False until the goals, messages and coach name have been read once: no empty state or default name flashes. */
    val loaded: Boolean = false,
    val coachName: String = "Arc",
    val goals: List<GoalState> = emptyList(),
    val focus: GoalState? = null,
    /** Current conversation; earlier ones only after "Show earlier conversation". */
    val items: List<ChatItem> = emptyList(),
    val hasEarlier: Boolean = false,
    val input: String = "",
    /** Waiting for the first token (thinking) or streaming (talking). */
    val awaiting: Boolean = false,
    val streaming: Boolean = false,
    val happyUntil: Long = 0,
    val notice: UiText? = null,
    // Study Mode
    val studySeconds: Int = 0,
    val studyNotes: String = "",
    val studyInput: String = "",
    val studyReply: String? = null,
    val studyPaused: Boolean = false,
) {
    /** typing → listening, awaiting → thinking, streaming → talking, card added → happy for 1.3 s. */
    fun mood(now: Long, typing: Boolean): MascotMood = when {
        awaiting -> MascotMood.Thinking
        streaming -> MascotMood.Talking
        now < happyUntil -> MascotMood.Happy
        typing -> MascotMood.Listening
        else -> MascotMood.Idle
    }
    val busy: Boolean get() = awaiting || streaming
}

@HiltViewModel
class CoachViewModel @Inject constructor(
    private val coach: CoachRepository,
    private val goalsRepo: GoalRepository,
    private val toaster: Toaster,
    session: SessionManager,
    private val analytics: app.thebridge.data.Analytics,
) : ViewModel() {
    private val local = MutableStateFlow(CoachUi())
    private val showEarlier = MutableStateFlow(false)

    val state: StateFlow<CoachUi> = combine(local, coach.messages, coach.conversation, goalsRepo.activeGoals(), combine(session.user.map { it?.coachName ?: "Arc" }, showEarlier, ::Pair)) { l, msgs, conv, goals, (name, earlier) ->
        val shown = if (earlier || conv == null) msgs else msgs.filter { it.conversation == conv }
        l.copy(
            loaded = true, coachName = name, goals = goals, focus = goals.firstOrNull { it.goal.id == focusId } ?: goals.firstOrNull(),
            hasEarlier = !earlier && msgs.any { it.conversation != conv },
            items = shown.mapIndexed { i, m -> ChatItem(m, coach.suggestion(m), i > 0 && shown[i - 1].conversation != m.conversation) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CoachUi())

    private var focusId: String? = null
    fun setFocus(goalId: String?) { focusId = goalId }

    fun onInput(v: String) = local.update { it.copy(input = v.take(2000), notice = null) }
    fun useChip(v: String) = onInput(v)
    fun showEarlier() { showEarlier.value = true }
    fun newConversation() = viewModelScope.launch { showEarlier.value = false; coach.newConversation() }

    private var sendJob: Job? = null

    fun send(study: Boolean = false) {
        val s = local.value
        val text = (if (study) s.studyInput else s.input).trim()
        if (text.isEmpty() || s.busy) return
        local.update { if (study) it.copy(studyInput = "", studyReply = null) else it.copy(input = "") }
        analytics.track(app.thebridge.data.Event.CoachMessageSent, "study" to study)
        run(study) { onFirst -> coach.send(text, state.value.focus?.goal?.id, if (study) "study" else "chat", onFirstToken = onFirst) }
    }

    fun retry(m: ChatMessageEntity) {
        if (local.value.busy) return
        run(false) { onFirst ->
            if (m.role == "coach") coach.retryInterrupted(m, state.value.focus?.goal?.id, onFirst)
            else coach.send(m.text, state.value.focus?.goal?.id, retryId = m.id, onFirstToken = onFirst)
        }
    }

    private fun run(study: Boolean, block: suspend (onFirst: () -> Unit) -> CoachProblem?) {
        local.update { it.copy(awaiting = true, notice = null) }
        sendJob = viewModelScope.launch {
            val problem = block { local.update { it.copy(awaiting = false, streaming = true) } }
            local.update { it.copy(awaiting = false, streaming = false) }
            if (study) local.update { it.copy(studyReply = state.value.items.lastOrNull { i -> i.message.role == "coach" }?.message?.id) }
            when (problem) {
                null -> Unit
                CoachProblem.Quota -> local.update { it.copy(notice = UiText.Res(R.string.coach_quota)) }
                CoachProblem.Offline -> toaster.show(UiText.Res(R.string.coach_offline), ToastKind.Warn)
                CoachProblem.Interrupted, CoachProblem.Failed -> Unit          // shown inline on the message
            }
        }
    }

    fun addSuggestion(item: ChatItem) = viewModelScope.launch {
        val goal = state.value.focus ?: return@launch
        if (coach.addSuggestion(item.message, goal.goal.id)) {
            local.update { it.copy(happyUntil = System.currentTimeMillis() + 1_300) }
            toaster.show(UiText.Res(R.string.coach_added_toast), ToastKind.Ok)
        }
    }
    fun dismissSuggestion(item: ChatItem) = viewModelScope.launch { coach.dismissSuggestion(item.message) }

    // --- Study Mode: a focus timer that pauses 10 s after the app goes to the background ---
    private var timer: Job? = null
    private var backgroundJob: Job? = null
    fun enterStudy() {
        local.update { it.copy(studySeconds = 0, studyReply = null, studyPaused = false) }
        startTimer()
    }
    private fun startTimer() {
        timer?.cancel()
        timer = viewModelScope.launch { while (isActive) { delay(1_000); local.update { it.copy(studySeconds = it.studySeconds + 1) } } }
    }
    fun exitStudy() { timer?.cancel(); backgroundJob?.cancel() }
    fun onStudyBackground() { backgroundJob = viewModelScope.launch { delay(10_000); timer?.cancel(); local.update { it.copy(studyPaused = true) } } }
    fun onStudyForeground() { backgroundJob?.cancel() }
    fun resumeStudy() { local.update { it.copy(studyPaused = false) }; startTimer() }
    fun onStudyNotes(v: String) = local.update { it.copy(studyNotes = v.take(20_000)) }
    fun onStudyInput(v: String) = local.update { it.copy(studyInput = v.take(2000)) }
    fun closeStudyReply() = local.update { it.copy(studyReply = null) }
}
