package app.thebridge.feature.coach

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Coach tab. Study Mode shares the same ViewModel (and conversation) through the activity-scoped [vm]. */
@Composable
/** [onOpenAlliance]: (goal id, join code) of an alliance the coach recommended. */
fun CoachRoute(vm: CoachViewModel, onStudy: () -> Unit, onOpenAlliance: (String, String) -> Unit = { _, _ -> }) {
    val ui by vm.state.collectAsStateWithLifecycle()
    CoachScreen(ui, CoachActions(
        onInput = vm::onInput, send = { vm.send() }, chip = vm::useChip, retry = { vm.retry(it.message) },
        add = { vm.addSuggestion(it) }, dismiss = { vm.dismissSuggestion(it) }, showEarlier = vm::showEarlier, newChat = { vm.newConversation() },
        study = { vm.enterStudy(); onStudy() }, focus = { vm.setFocus(it) },
        openAlliance = { item -> val g = ui.focus?.goal?.id; val a = item.alliance; if (g != null && a != null) onOpenAlliance(g, a.code) },
    ))
}

@Composable
fun StudyRoute(vm: CoachViewModel, onExit: () -> Unit) {
    val ui by vm.state.collectAsStateWithLifecycle()
    StudyModeScreen(ui, vm::onStudyNotes, vm::onStudyInput, { vm.send(study = true) }, { vm.addSuggestion(it) }, vm::closeStudyReply,
        vm::onStudyBackground, vm::onStudyForeground, vm::resumeStudy) { vm.exitStudy(); onExit() }
}

@Composable
fun rememberCoachViewModel(): CoachViewModel = hiltViewModel()
