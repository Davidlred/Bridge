package app.thebridge.feature.journal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Journal tab, or the journal pushed from Today ([goalId] set, with a back link to Today). */
@Composable
fun JournalRoute(goalId: String? = null, onBack: (() -> Unit)? = null, vm: JournalViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(goalId) { vm.load(goalId) }
    JournalScreen(ui, JournalActions(
        tab = vm::showTab, goal = vm::selectGoal, mood = vm::onMood, text = vm::onText,
        lock = { vm.lockIn { onBack?.invoke() } }, back = onBack, filter = vm::filter, open = { open = it.entry.id }, keep = { i, other -> vm.keep(i, other) },
    ), vm::dayIndex)
    open?.let { id ->
        val item = ui.history.firstOrNull { it.entry.id == id }
        if (item == null) open = null
        else EntrySheet(item, vm.dayIndex(item.entry), onSave = { vm.saveEntry(item.entry, it); open = null }, onDismiss = { open = null })
    }
}
