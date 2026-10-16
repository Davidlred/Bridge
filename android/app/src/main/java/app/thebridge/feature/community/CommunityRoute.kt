package app.thebridge.feature.community

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.thebridge.R
import app.thebridge.data.local.AllianceMessageEntity
import app.thebridge.ui.components.BridgeSheet
import app.thebridge.ui.components.PrimaryButton
import app.thebridge.ui.components.SecondaryButton
import app.thebridge.ui.components.SheetTitle
import app.thebridge.ui.theme.BType
import app.thebridge.ui.theme.LocalBridgeColors

/** Community inside a goal: wires [CommunityScreen] and its sheets to the ViewModel. */
@Composable
fun CommunityRoute(onBack: () -> Unit, onOpenChat: (String) -> Unit, onPlay: (String) -> Unit = {}, onBoard: (String) -> Unit = {}, onDms: () -> Unit = {}, vm: CommunityViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val shareText = @Composable { a: app.thebridge.data.remote.AllianceDto -> stringResource(R.string.ally_invite_share, a.name, "https://thebridge.app/join/${a.inviteCode}", a.inviteCode) }
    var inviteFor by remember { mutableStateOf<app.thebridge.data.remote.AllianceDto?>(null) }
    var paywall by remember { mutableStateOf(false) }
    inviteFor?.let { a ->
        val text = shareText(a)
        // The system share sheet: the invite is a link with the code, nothing else about the goal.
        androidx.compose.runtime.LaunchedEffect(a) {
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
            inviteFor = null
        }
    }
    CommunityScreen(ui, CommunityActions(
        back = onBack, retry = { vm.refresh() }, cycleShare = { vm.cycleShare() }, startPreview = vm::startPreview, endPreview = vm::endPreview, showTab = vm::showTab,
        react = { p, r -> vm.react(p, r) }, postMenu = vm::openPostMenu, create = vm::openCreate, join = vm::openJoin, select = vm::select,
        invite = { inviteFor = it }, sampleInvite = vm::sampleInvite, openChat = { onOpenChat(it.id) }, options = vm::askLeave, applyNow = { vm.applyNow(it) },
        openDms = onDms, alliancePlus = { paywall = true }, playGame = { onPlay(it.id) }, nudge = { vm.nudge(it) }, openBoard = { onBoard(it.id) },
    ))
    if (paywall) app.thebridge.feature.paywall.PaywallSheet(app.thebridge.data.Product.AlliancePlus, onDismiss = { paywall = false; vm.refresh() })
    when (val s = ui.sheet) {
        is CommunitySheet.Create -> CreateAllianceSheet(s, ui.goal, vm::onName, vm::onShared, vm::create, vm::closeSheet)
        is CommunitySheet.Join -> JoinAllianceSheet(s, vm::onCode, vm::join, vm::backToCode, vm::closeSheet)
        is CommunitySheet.PostMenu -> PostMenuSheet(s.post, onReport = { vm.report(s.post, it) }, onBlock = { vm.block(s.post) }, onDismiss = vm::closeSheet)
        is CommunitySheet.Leave -> LeaveAllianceSheet(s.alliance, onLeave = { vm.leave(s.alliance) }, onDismiss = vm::closeSheet)
        null -> Unit
    }
}

@Composable
fun AllianceChatRoute(onBack: () -> Unit, vm: AllianceChatViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    var reporting by remember { mutableStateOf<AllianceMessageEntity?>(null) }
    AllianceChatScreen(ui, ChatActions(back = onBack, onInput = vm::onInput, send = vm::send, retry = { vm.retry(it) }, discard = { vm.discard(it) }, report = { reporting = it }))
    reporting?.let { m ->
        val c = LocalBridgeColors.current
        BridgeSheet({ reporting = null }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SheetTitle(stringResource(R.string.report_title))
                Text(stringResource(R.string.report_body), style = BType.body(14, lineHeight = 1.5f), color = c.mut)
                PrimaryButton(stringResource(R.string.report_message), { vm.reportMessage(m); reporting = null })
                SecondaryButton(stringResource(R.string.ally_stay), { reporting = null })
            }
        }
    }
}
