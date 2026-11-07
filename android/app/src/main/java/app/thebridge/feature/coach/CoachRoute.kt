package app.thebridge.feature.coach

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.thebridge.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Coach tab. Study Mode shares the same ViewModel (and conversation) through the activity-scoped [vm]. */
@Composable
/** [onOpenAlliance]: (goal id, join code) of an alliance the coach recommended. */
fun CoachRoute(vm: CoachViewModel, onStudy: () -> Unit, onOpenAlliance: (String, String) -> Unit = { _, _ -> }) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    // Back from Settings (alarms, notifications): show what changed.
    LifecycleResumeEffect(Unit) { vm.refreshSettings(); onPauseOrDispose {} }
    val prompt = stringResource(R.string.coach_speak_prompt, ui.coachName)
    val speech = remember { Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM) }
    val canListen = remember { speech.resolveActivity(ctx.packageManager) != null }
    val listen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(vm::spoken)
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshSettings() }
    CoachScreen(ui, CoachActions(
        onInput = vm::onInput, send = { vm.send() }, chip = vm::useChip, retry = { vm.retry(it.message) },
        add = { vm.addSuggestion(it) }, dismiss = { vm.dismissSuggestion(it) }, showEarlier = vm::showEarlier, newChat = { vm.newConversation() },
        study = { vm.enterStudy(); onStudy() }, focus = { vm.setFocus(it) },
        openAlliance = { item -> val g = ui.focus?.goal?.id; val a = item.alliance; if (g != null && a != null) onOpenAlliance(g, a.code) },
        setReminder = { vm.setReminder(it) }, cancelReminder = { vm.cancelReminder(it) }, call = vm::callFrom,
        allowExact = { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", ctx.packageName, null))) },
        allowNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            else ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        },
        listen = if (canListen) ({ listen.launch(Intent(speech).putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)) }) else null,
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
