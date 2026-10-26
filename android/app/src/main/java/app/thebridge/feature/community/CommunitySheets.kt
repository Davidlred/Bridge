package app.thebridge.feature.community

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.thebridge.R
import app.thebridge.data.GoalState
import app.thebridge.data.remote.AllianceDto
import app.thebridge.data.remote.InvitePreviewDto
import app.thebridge.data.remote.PostDto
import app.thebridge.ui.components.BridgeField
import app.thebridge.ui.components.BridgeSheet
import app.thebridge.ui.components.PrimaryButton
import app.thebridge.ui.components.SecondaryButton
import app.thebridge.ui.components.SheetTitle
import app.thebridge.ui.components.UiText
import app.thebridge.ui.theme.BType
import app.thebridge.ui.theme.LocalBridgeColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Name and plan type. A shared plan shows what members will get. */
@Composable
fun CreateAllianceSheet(s: CommunitySheet.Create, goal: GoalState?, onName: (String) -> Unit, onShared: (Boolean) -> Unit, onCreate: () -> Unit, onDismiss: () -> Unit,
                        onListed: (Boolean) -> Unit = {}, onAbout: (String) -> Unit = {}) {
    BridgeSheet(onDismiss) { CreateAllianceContent(s, goal, onName, onShared, onCreate, onListed, onAbout) }
}

@Composable
fun CreateAllianceContent(s: CommunitySheet.Create, goal: GoalState?, onName: (String) -> Unit, onShared: (Boolean) -> Unit, onCreate: () -> Unit,
                          onListed: (Boolean) -> Unit = {}, onAbout: (String) -> Unit = {}) {
    val c = LocalBridgeColors.current
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SheetTitle(stringResource(R.string.create_title))
        BridgeField(stringResource(R.string.create_name), s.name, onName, stringResource(R.string.create_name_hint), error = s.error?.resolve(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { onCreate() }))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.create_plan), style = BType.label(11f, 0.12f), color = c.mut)
            PlanOption(!s.shared, stringResource(R.string.create_own), stringResource(R.string.create_own_sub)) { onShared(false) }
            PlanOption(s.shared, stringResource(R.string.create_shared), stringResource(R.string.create_shared_sub)) { onShared(true) }
        }
        if (s.shared && goal != null) Column(Modifier.fillMaxWidth().background(c.accTint, RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.create_members_get, goal.goal.label.uppercase(Locale.getDefault())), style = BType.label(10f, 0.12f), color = c.accInk)
            Text(goal.goal.statement, style = BType.body(14, FontWeight.Medium, 1.45f), color = c.ink)
            Text(stringResource(R.string.create_shared_meta, LocalDate.parse(goal.goal.deadline).format(DATE), goal.tasks.size), style = BType.body(12), color = c.ink2)
            Text(stringResource(R.string.create_shared_note), style = BType.body(12, lineHeight = 1.45f), color = c.mut)
        }
        ListingSwitch(s.listed, onListed)
        if (s.listed) BridgeField(stringResource(R.string.create_about), s.about, onAbout, stringResource(R.string.create_about_hint))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(if (s.listed) R.drawable.ic_groups else R.drawable.ic_lock), null, tint = c.mut, modifier = Modifier.size(16.dp))
            Text(stringResource(if (s.listed) R.string.create_findable else R.string.create_private), style = BType.body(12, lineHeight = 1.4f), color = c.mut)
        }
        PrimaryButton(stringResource(if (s.shared) R.string.create_cta_shared else R.string.create_cta_own), onCreate, loading = s.busy)
    }
}

/** "Let people find this alliance": Arc can suggest it to people with a goal like it, and they join without a code. */
@Composable
fun ListingSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalBridgeColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, c.line, RoundedCornerShape(16.dp))
        .toggleable(on, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(R.string.ally_listed), style = BType.body(15, FontWeight.Medium), color = c.ink)
            Text(stringResource(R.string.ally_listed_sub), style = BType.body(12, lineHeight = 1.4f), color = c.mut)
        }
        Box(Modifier.size(width = 44.dp, height = 24.dp).background(if (on) c.okFill else c.track, CircleShape).padding(2.dp), contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.size(20.dp).background(c.s1, CircleShape))
        }
    }
}

@Composable
private fun PlanOption(on: Boolean, title: String, sub: String, onClick: () -> Unit) {
    val c = LocalBridgeColors.current
    val shape = RoundedCornerShape(16.dp)
    Row(Modifier.fillMaxWidth().clip(shape).border(if (on) 1.5.dp else 1.dp, if (on) c.acc else c.line, shape).clickable(role = Role.RadioButton, onClick = onClick)
        .semantics { selected = on }.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(if (on) R.drawable.ic_radio_on else R.drawable.ic_radio_off), null, tint = if (on) c.acc else c.mut, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = BType.body(15, FontWeight.SemiBold), color = c.ink)
            Text(sub, style = BType.body(13, lineHeight = 1.45f), color = c.mut)
        }
    }
}

/** Code entry, then (for a shared plan) the invite preview with the pace note. */
@Composable
fun JoinAllianceSheet(s: CommunitySheet.Join, onCode: (String) -> Unit, onJoin: () -> Unit, onBack: () -> Unit, onDismiss: () -> Unit) {
    BridgeSheet(onDismiss) { JoinAllianceContent(s, onCode, onJoin, onBack) }
}

@Composable
fun JoinAllianceContent(s: CommunitySheet.Join, onCode: (String) -> Unit, onJoin: () -> Unit, onBack: () -> Unit) {
    val p = s.preview
    if (p == null) Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SheetTitle(stringResource(R.string.join_title))
        BridgeField(stringResource(R.string.join_code), s.code, onCode, stringResource(R.string.join_code_hint), error = s.error?.resolve(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { onJoin() }))
        PrimaryButton(stringResource(R.string.join_cta), onJoin, loading = s.busy)
    } else InvitePreview(p, s.error, s.busy, onJoin, onBack)
}

@Composable
private fun InvitePreview(p: InvitePreviewDto, error: UiText?, busy: Boolean, onJoin: () -> Unit, onBack: () -> Unit) {
    val c = LocalBridgeColors.current
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.ally_shared_plan), style = BType.label(10f, 0.12f), color = c.accInk)
            SheetTitle(p.name)
            Text("${stringResource(R.string.ally_set_by, p.lead)} · ${stringResource(R.string.join_preview_seats, p.members, p.spotsLeft)}", style = BType.body(13), color = c.mut)
        }
        Column(Modifier.fillMaxWidth().background(c.s1, RoundedCornerShape(18.dp)).border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(p.goal.orEmpty(), style = BType.body(15, FontWeight.Medium, 1.45f), color = c.ink)
            p.deadline?.let { d ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(painterResource(R.drawable.ic_event), null, tint = c.mut, modifier = Modifier.size(16.dp))
                    Text(stringResource(R.string.join_due, LocalDate.parse(d).format(DATE)), style = BType.body(13), color = c.ink2)
                }
            }
            HorizontalDivider(color = c.line)
            Text(stringResource(R.string.join_daily_tasks), style = BType.label(10f, 0.12f), color = c.mut)
            p.tasks.forEach { t ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(t.title, style = BType.body(14), color = c.ink, modifier = Modifier.weight(1f))
                    t.timeOfDay?.let { Text(it, style = BType.label(11f, 0f), color = c.mut) }
                }
            }
        }
        p.pace?.let { pace ->
            Row(Modifier.fillMaxWidth().background(c.accTint, RoundedCornerShape(14.dp)).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(painterResource(R.drawable.ic_schedule), null, tint = c.accInk, modifier = Modifier.size(18.dp))
                Text(if (pace.groupDay > 1) stringResource(R.string.join_pace, pace.groupDay - 1, pace.groupDay, pace.groupTotal, pace.yourTotal) else stringResource(R.string.join_pace_fresh, pace.yourTotal),
                    style = BType.body(13, lineHeight = 1.45f), color = c.accInk)
            }
        }
        if (error != null) Text(error.resolve(), style = BType.body(13), color = c.bad, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        PrimaryButton(stringResource(R.string.join_confirm_shared), onJoin, loading = busy)
        SecondaryButton(stringResource(R.string.join_different), onBack)
    }
}

/** Report or block the person behind a post. */
@Composable
fun PostMenuSheet(post: PostDto, onReport: (String) -> Unit, onBlock: () -> Unit, onDismiss: () -> Unit) {
    var blocking by rememberSaveable { mutableStateOf(false) }
    val c = LocalBridgeColors.current
    BridgeSheet(onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!blocking) {
                SheetTitle(stringResource(R.string.report_title))
                Text(stringResource(R.string.report_body), style = BType.body(14, lineHeight = 1.5f), color = c.mut)
                listOf(R.string.report_reason_spam to "spam", R.string.report_reason_harassment to "harassment", R.string.report_reason_other to "other").forEach { (label, reason) ->
                    SecondaryButton(stringResource(label), { onReport(reason) })
                }
                if (post.userId != null) Text(stringResource(R.string.block_person, post.name), style = BType.body(15, FontWeight.SemiBold), color = c.bad,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { blocking = true }.padding(vertical = 14.dp))
            } else {
                SheetTitle(stringResource(R.string.block_person, post.name))
                Text(stringResource(R.string.block_body), style = BType.body(14, lineHeight = 1.5f), color = c.mut)
                PrimaryButton(stringResource(R.string.block_person, post.name), onBlock, container = c.bad, content = c.s1)
                SecondaryButton(stringResource(R.string.ally_stay), { blocking = false })
            }
        }
    }
}

@Composable
fun LeaveAllianceSheet(a: AllianceDto, onLeave: () -> Unit, onDismiss: () -> Unit) {
    val c = LocalBridgeColors.current
    BridgeSheet(onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SheetTitle(stringResource(R.string.ally_leave_title, a.name))
            val body = when { a.role == "owner" && a.memberCount > 1 -> R.string.ally_leave_body_lead; a.isShared && a.role != "owner" -> R.string.ally_leave_body_member_shared; else -> R.string.ally_leave_body }
            Text(stringResource(body), style = BType.body(14, lineHeight = 1.5f), color = c.mut)
            Box(Modifier.height(4.dp))
            PrimaryButton(stringResource(R.string.ally_leave_confirm), onLeave, container = c.bad, content = c.s1)
            SecondaryButton(stringResource(R.string.ally_stay), onDismiss)
        }
    }
}

private val DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
