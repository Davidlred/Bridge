package app.thebridge.feature.journal

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.thebridge.R
import app.thebridge.data.GoalRepository
import app.thebridge.data.GoalState
import app.thebridge.data.JournalItem
import app.thebridge.data.JournalRepository
import app.thebridge.data.NetworkMonitor
import app.thebridge.data.local.JournalEntity
import app.thebridge.data.local.LocalDay
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

enum class SaveState { Idle, Saving, Saved, SavedOffline }
enum class JournalTab { Today, History }

data class JournalUi(
    val tab: JournalTab = JournalTab.Today,
    val goals: List<GoalState> = emptyList(),
    val goalId: String? = null,
    val mood: Int? = null,
    val text: String = "",
    val save: SaveState = SaveState.Idle,
    val savedAt: Long = 0,
    val history: List<JournalItem> = emptyList(),
    /** History filter: null = all goals. */
    val filter: String? = null,
    val today: String = LocalDay.today(),
    val conflict: JournalItem? = null,
) {
    val goal: GoalState? get() = goals.firstOrNull { it.goal.id == goalId }
    val words: Int get() = text.trim().split(Regex("""\s+""")).count { it.isNotEmpty() }
    val canLock: Boolean get() = text.isNotBlank()
    val shownHistory: List<JournalItem> get() = history.filter { filter == null || it.entry.goalId == filter }
}

@HiltViewModel
class JournalViewModel @Inject constructor(
    private val saved: SavedStateHandle,
    private val journal: JournalRepository,
    private val toaster: Toaster,
    private val network: NetworkMonitor,
    goals: GoalRepository,
    private val analytics: app.thebridge.data.Analytics,
) : ViewModel() {
    private var counted = mutableSetOf<String>()
    private val draft = MutableStateFlow(JournalUi(goalId = saved["goalId"]))

    val state: StateFlow<JournalUi> = combine(draft, goals.activeGoals(), journal.entries()) { d, gs, entries ->
        val goalId = d.goalId?.takeIf { id -> gs.any { it.goal.id == id } } ?: gs.firstOrNull()?.goal?.id
        d.copy(goals = gs, goalId = goalId, history = entries,
            conflict = entries.firstOrNull { it.conflict != null && it.entry.goalId == goalId && it.entry.localDate == d.today })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), draft.value)

    private var loadedFor: Pair<String?, String>? = null

    /** Loads today's entry for the selected goal into the editor (once per goal/day, so typing isn't overwritten by sync). */
    fun load(goalId: String?) = viewModelScope.launch {
        val today = LocalDay.today()
        val id = goalId ?: state.first { it.goals.isNotEmpty() || it.goalId == null }.goalId
        if (loadedFor == id to today) return@launch
        loadedFor = id to today
        saved["goalId"] = id
        val e = journal.current(id, today)
        draft.update { it.copy(goalId = id, today = today, mood = e?.mood, text = e?.body.orEmpty(), save = SaveState.Idle) }
    }

    fun selectGoal(id: String) { loadedFor = null; draft.update { it.copy(goalId = id) }; load(id) }
    fun showTab(t: JournalTab) = draft.update { it.copy(tab = t) }
    fun filter(goalId: String?) = draft.update { it.copy(filter = goalId) }

    private var saveJob: Job? = null
    fun onText(v: String) { draft.update { it.copy(text = v.take(JournalRepository.MAX_LENGTH), save = SaveState.Saving) }; scheduleSave() }
    fun onMood(i: Int) { draft.update { it.copy(mood = if (it.mood == i) null else i, save = SaveState.Saving) }; scheduleSave(0) }

    /** Autosave shortly after typing stops. Saving is local, so it works offline ("Saved on this phone"). */
    private fun scheduleSave(wait: Long = 600) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(wait)
            val s = draft.value
            journal.save(s.goalId, s.today, s.mood, s.text)
            if (counted.add("${s.goalId}|${s.today}")) analytics.track(app.thebridge.data.Event.JournalSaved, "mood" to (s.mood ?: 0))       // once per entry, not per keystroke
            draft.update { it.copy(save = if (network.online.value) SaveState.Saved else SaveState.SavedOffline, savedAt = System.currentTimeMillis()) }
        }
    }

    /** "Lock in today": make sure the latest text is saved before leaving. */
    fun lockIn(then: () -> Unit) = viewModelScope.launch {
        saveJob?.cancel()
        val s = draft.value
        journal.save(s.goalId, s.today, s.mood, s.text)
        toaster.show(UiText.Res(R.string.journal_locked_toast), ToastKind.Ok)
        then()
    }

    /** Two versions of today's entry: keep one (or the merged text the user wrote). */
    fun keep(item: JournalItem, useOther: Boolean) = viewModelScope.launch {
        val pick = if (useOther) item.conflict!! else item.entry
        journal.resolve(item.entry, pick.mood, pick.body)
        loadedFor = null; load(item.entry.goalId)
    }

    /** Edit a past entry from History. */
    fun saveEntry(e: JournalEntity, body: String) = viewModelScope.launch {
        journal.save(e.goalId, e.localDate, e.mood, body)
        if (e.localDate == draft.value.today && e.goalId == draft.value.goalId) draft.update { it.copy(text = body.take(JournalRepository.MAX_LENGTH)) }
        toaster.show(UiText.Res(R.string.journal_entry_saved), ToastKind.Ok)
    }

    fun dayIndex(e: JournalEntity): Int? = state.value.goals.firstOrNull { it.goal.id == e.goalId }?.let {
        (java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(it.goal.startDate), LocalDate.parse(e.localDate)) + 1).toInt()
    }
}
