package app.thebridge.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.thebridge.R
import app.thebridge.feature.coach.CoachMascot
import app.thebridge.feature.coach.MascotMood
import app.thebridge.ui.components.BridgeSheet
import app.thebridge.ui.components.PrimaryButton
import app.thebridge.ui.components.SecondaryButton
import app.thebridge.ui.components.SheetTitle
import app.thebridge.ui.components.UiText
import app.thebridge.ui.theme.BType
import app.thebridge.ui.theme.LocalBridgeColors
import app.thebridge.ui.theme.LocalReduceMotion
import app.thebridge.ui.theme.Radius
import app.thebridge.ui.theme.Space
import java.time.LocalDate
import app.thebridge.data.reminders.ReminderSettings
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale

// Tab roots for milestone 1: real chrome and empty states. Goal cards, chat and entries arrive with milestones 2 and 3.

@Composable
private fun TabPage(content: @Composable ColumnScope.() -> Unit) = Column(
    Modifier.fillMaxSize().background(LocalBridgeColors.current.bg).verticalScroll(rememberScrollState()).padding(horizontal = Space.screen, vertical = 16.dp),
    verticalArrangement = Arrangement.spacedBy(Space.section), content = content,
)

@Composable
private fun EmptyCard(title: String, body: String) {
    val c = LocalBridgeColors.current
    Column(Modifier.fillMaxWidth().background(c.s1, RoundedCornerShape(Radius.card)).border(1.dp, c.line, RoundedCornerShape(Radius.card)).padding(Space.card), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = BType.display(20), color = c.ink)
        Text(body, style = BType.body(14, lineHeight = 1.5f), color = c.mut)
    }
}

@Composable
fun ProfileScreen(
    name: String, email: String?, memberSince: LocalDate?, onLogOut: () -> Unit, onDelete: () -> Unit,
    reminders: ReminderSettings = ReminderSettings(), onDaily: () -> Unit = {}, onRisk: () -> Unit = {},
    hasPhoto: Boolean = false, onRemovePhoto: () -> Unit = {},
    coachName: String = "Arc", onRenameCoach: (String) -> Unit = {}, coachReadsJournal: Boolean = false, onCoachReadsJournal: (Boolean) -> Unit = {},
    share: app.thebridge.data.ShareLevel = app.thebridge.data.ShareLevel.Streaks, onShare: () -> Unit = {}, onHall: (() -> Unit)? = null, onExport: () -> Unit = {},
) {
    var renaming by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val c = LocalBridgeColors.current
    TabPage {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(c.goalCards[0]), contentAlignment = Alignment.Center) {
                Text(name.take(1).uppercase(), style = BType.display(25), color = c.goalInk)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = BType.display(22), color = c.ink, modifier = Modifier.semantics { heading() })
                if (memberSince != null) Text(stringResource(R.string.profile_member_since, "${memberSince.month.getDisplayName(DateTextStyle.SHORT, Locale.getDefault())} ${memberSince.year}".uppercase()), style = BType.label(10f), color = c.mut)
                if (email != null) Text(email, style = BType.body(13), color = c.mut)
            }
        }
        if (onHall != null) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.s1).border(1.dp, c.accLine, RoundedCornerShape(20.dp))
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onHall).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_shield), null, tint = c.acc, modifier = Modifier.size(26.dp))
            Text(stringResource(R.string.hall_title), style = BType.display(16), color = c.ink, modifier = Modifier.weight(1f))
            androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_chevron_right), null, tint = c.mut, modifier = Modifier.size(22.dp))
        }
        SettingsSection(stringResource(R.string.settings_notifications)) {
            SettingsValue(stringResource(R.string.settings_daily_reminder), reminders.daily?.let(::clock) ?: stringResource(R.string.settings_off), onDaily)
            HorizontalDivider(color = c.line)
            SettingsValue(stringResource(R.string.settings_streak_risk), reminders.streakRisk?.let(::clock) ?: stringResource(R.string.settings_off), onRisk)
        }
        SettingsSection(stringResource(R.string.settings_coach)) {
            SettingsValue(stringResource(R.string.settings_coach_name), coachName) { renaming = true }
            HorizontalDivider(color = c.line)
            SettingsToggle(stringResource(R.string.settings_coach_journal), stringResource(R.string.settings_coach_journal_sub), coachReadsJournal, onCoachReadsJournal)
        }
        SettingsSection(stringResource(R.string.settings_privacy)) {
            SettingsValue(stringResource(R.string.settings_share), stringResource(when (share) {
                app.thebridge.data.ShareLevel.Nothing -> R.string.settings_share_nothing; app.thebridge.data.ShareLevel.Streaks -> R.string.settings_share_streaks; app.thebridge.data.ShareLevel.All -> R.string.settings_share_all
            }), onShare)
        }
        if (hasPhoto) SettingsSection(stringResource(R.string.settings_photo)) {
            SettingsLink(stringResource(R.string.settings_remove_photo), danger = true, onClick = onRemovePhoto)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_account).uppercase(), style = BType.label(10f), color = c.mut, modifier = Modifier.semantics { heading() })
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(c.s1).border(1.dp, c.line, RoundedCornerShape(Radius.card))) {
                SettingsLink(stringResource(R.string.settings_export), danger = false, onClick = onExport)
                HorizontalDivider(color = c.line)
                SettingsLink(stringResource(R.string.settings_log_out), danger = false, onClick = onLogOut)
                HorizontalDivider(color = c.line)
                SettingsLink(stringResource(R.string.settings_delete_account), danger = true, onClick = onDelete)
            }
        }
    }
    if (renaming) RenameCoachSheet(coachName, onSave = { onRenameCoach(it); renaming = false }, onDismiss = { renaming = false })
}

@Composable
private fun SettingsToggle(label: String, sub: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalBridgeColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).toggleable(on, role = Role.Switch, onValueChange = onChange).padding(horizontal = Space.card, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = BType.body(15, FontWeight.Medium), color = c.ink)
            if (sub != null) Text(sub, style = BType.body(12), color = c.mut)
        }
        // 44×24 track, knob 20; on = okFill (as in the prototype)
        Box(Modifier.size(width = 44.dp, height = 24.dp).background(if (on) c.okFill else c.track, CircleShape).padding(2.dp), contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.size(20.dp).background(c.s1, CircleShape))
        }
    }
}

@Composable
private fun RenameCoachSheet(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(current) }
    BridgeSheet(onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SheetTitle(stringResource(R.string.coach_rename_title))
            app.thebridge.ui.components.BridgeField(stringResource(R.string.settings_coach_name).uppercase(), name, { name = it.take(20) }, stringResource(R.string.coach_rename_hint))
            PrimaryButton(stringResource(R.string.save), { onSave(name) })
        }
    }
}

private fun clock(t: java.time.LocalTime): String = t.format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT))

@Composable
private fun SettingsSection(title: String, rows: @Composable ColumnScope.() -> Unit) {
    val c = LocalBridgeColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), style = BType.label(10f), color = c.mut, modifier = Modifier.semantics { heading() })
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(c.s1).border(1.dp, c.line, RoundedCornerShape(Radius.card)), content = rows)
    }
}

@Composable
private fun SettingsValue(label: String, value: String, onClick: () -> Unit) {
    val c = LocalBridgeColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = Space.card, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = BType.body(15, FontWeight.Medium), color = c.ink, modifier = Modifier.weight(1f))
        Text(value, style = BType.body(14), color = c.mut)
    }
}

@Composable
private fun SettingsLink(label: String, danger: Boolean, onClick: () -> Unit) {
    val c = LocalBridgeColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = Space.card, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = BType.body(15, FontWeight.Medium), color = if (danger) c.bad else c.ink, modifier = Modifier.weight(1f))
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = c.mut2, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun DeleteAccountSheet(busy: Boolean, error: UiText?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = LocalBridgeColors.current
    BridgeSheet(onDismiss, dismissible = !busy) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SheetTitle(stringResource(R.string.delete_title))
            Text(stringResource(R.string.delete_body), style = BType.body(15, lineHeight = 1.5f), color = c.ink2)
            Text(stringResource(R.string.delete_subscription_note), style = BType.body(13), color = c.mut)
            if (error != null) Text(error.resolve(), style = BType.body(13), color = c.bad, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            PrimaryButton(stringResource(R.string.delete_confirm), onConfirm, loading = busy, container = c.bad, content = c.ink)
            SecondaryButton(stringResource(R.string.delete_cancel), onDismiss, enabled = !busy)
        }
    }
}

/** Logging out wipes local data, so warn first when something hasn't reached the server. */
@Composable
fun LogOutSheet(unsynced: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = LocalBridgeColors.current
    BridgeSheet(onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SheetTitle(stringResource(R.string.settings_log_out))
            Text(pluralStringResource(R.plurals.logout_unsynced_warning, unsynced, unsynced), style = BType.body(15, lineHeight = 1.5f), color = c.ink2)
            PrimaryButton(stringResource(R.string.stay_logged_in), onDismiss)
            SecondaryButton(stringResource(R.string.logout_anyway), onConfirm)
        }
    }
}

/** 36dp strip under the status bar while there is no connection. */
@Composable
fun OfflineBanner(queued: Int, modifier: Modifier = Modifier) {
    val c = LocalBridgeColors.current
    Row(
        modifier.fillMaxWidth().background(c.warnTint).heightIn(min = 36.dp).padding(horizontal = Space.screen, vertical = 8.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(R.drawable.ic_cloud_off), contentDescription = null, tint = c.warn, modifier = Modifier.size(16.dp))
        Text(stringResource(R.string.offline_banner), style = BType.body(12, FontWeight.Medium), color = c.warn, modifier = Modifier.weight(1f))
        Text(if (queued > 0) pluralStringResource(R.plurals.offline_queued, queued, queued) else stringResource(R.string.offline_cached), style = BType.label(11f, 0.06f), color = c.warn)
    }
}

/** Blocking screen after the server answers 426. Local data is untouched. */
@Composable
fun UpgradeScreen(onOpenStore: () -> Unit) {
    val c = LocalBridgeColors.current
    Column(Modifier.fillMaxSize().background(c.bg).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
        Text(stringResource(R.string.upgrade_title), style = BType.display(30), color = c.ink, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.upgrade_body), style = BType.body(15, lineHeight = 1.5f), color = c.mut)
        PrimaryButton(stringResource(R.string.upgrade_cta), onOpenStore)
    }
}
