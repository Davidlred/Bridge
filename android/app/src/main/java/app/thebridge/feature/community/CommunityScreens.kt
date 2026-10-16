package app.thebridge.feature.community

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.thebridge.R
import app.thebridge.data.Reaction
import app.thebridge.data.ShareLevel
import app.thebridge.data.remote.AllianceDto
import app.thebridge.data.remote.AllianceFeedDto
import app.thebridge.data.remote.MemberDto
import app.thebridge.data.remote.PostDto
import app.thebridge.data.remote.ReactionCounts
import app.thebridge.ui.theme.BType
import app.thebridge.ui.theme.LocalBridgeColors
import app.thebridge.ui.theme.Motion
import app.thebridge.ui.theme.Space
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Callbacks from the Community screen, so it stays stateless and snapshot-testable. */
data class CommunityActions(
    val back: () -> Unit = {}, val retry: () -> Unit = {}, val cycleShare: () -> Unit = {}, val startPreview: () -> Unit = {}, val endPreview: () -> Unit = {},
    val showTab: (CommunityTab) -> Unit = {}, val react: (PostDto, Reaction) -> Unit = { _, _ -> }, val postMenu: (PostDto) -> Unit = {},
    val create: () -> Unit = {}, val join: () -> Unit = {}, val select: (String) -> Unit = {}, val invite: (AllianceDto) -> Unit = {}, val sampleInvite: () -> Unit = {},
    val openChat: (AllianceDto) -> Unit = {}, val options: (AllianceDto) -> Unit = {}, val applyNow: (AllianceDto) -> Unit = {},
    val openDms: () -> Unit = {}, val playGame: (AllianceDto) -> Unit = {}, val nudge: (AllianceDto) -> Unit = {}, val openBoard: (AllianceDto) -> Unit = {},
)

@Composable
fun CommunityScreen(ui: CommunityUi, actions: CommunityActions, today: LocalDate = LocalDate.now(), now: Long = System.currentTimeMillis()) {
    val c = LocalBridgeColors.current
    val label = ui.goal?.goal?.label.orEmpty()
    Column(Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState()).padding(start = Space.screen, end = Space.screen, top = 6.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.offset(x = (-8).dp).heightIn(min = 44.dp).clickable(role = Role.Button, onClickLabel = stringResource(R.string.back), onClick = actions.back).padding(end = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_chevron_left), null, tint = c.ink, modifier = Modifier.size(24.dp))
            Text(label, style = BType.body(15, FontWeight.Medium), color = c.ink, maxLines = 1)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.community_title), style = BType.display(25), color = c.ink, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.community_sub, label), style = BType.body(13), color = c.mut)
            }
            if (!ui.locked && !ui.previewing) Box(Modifier.size(44.dp).clip(CircleShape).background(c.s1).border(1.dp, c.line, CircleShape)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.dm_open), onClick = actions.openDms), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_lock), stringResource(R.string.dm_open), tint = c.ink, modifier = Modifier.size(20.dp))
            }
            if (!ui.locked) ShareChip(ui.share, actions.cycleShare)
        }
        when {
            ui.locked -> Locked(ui.streak, actions.startPreview)
            else -> {
                if (ui.previewing) PreviewBanner(actions.endPreview)
                Segments(ui.tab, actions.showTab)
                AnimatedContent(ui.tab, label = "community", transitionSpec = { fadeIn(tween(Motion.fade)) togetherWith fadeOut(tween(Motion.fade)) }) { tab ->
                    if (tab == CommunityTab.Feed) Feed(ui, actions, label, now) else Alliances(ui, actions, label, today, now)
                }
            }
        }
    }
}

@Composable
private fun ShareChip(share: ShareLevel, onClick: () -> Unit) {
    val c = LocalBridgeColors.current
    val text = stringResource(when (share) { ShareLevel.Nothing -> R.string.share_nothing; ShareLevel.Streaks -> R.string.share_streaks; ShareLevel.All -> R.string.share_all })
    val a11y = stringResource(R.string.share_a11y, text)
    Row(Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) { contentDescription = a11y }, verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.heightIn(min = 36.dp).background(c.s1, RoundedCornerShape(18.dp)).border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(R.drawable.ic_visibility), null, tint = c.ink2, modifier = Modifier.size(16.dp))
            Text(text, style = BType.body(12, FontWeight.Medium), color = c.ink2, maxLines = 1)
        }
    }
}

@Composable
private fun Locked(streak: Int, onPreview: () -> Unit) {
    val c = LocalBridgeColors.current
    Box(Modifier.fillMaxWidth().heightIn(min = 520.dp)) {
        // Blurred stand-ins for the feed behind the lock card
        Column(Modifier.fillMaxWidth().blur(6.dp).alpha(.45f).clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(120, 96, 120, 96).forEach { Box(Modifier.fillMaxWidth().height(it.dp).background(c.s1, RoundedCornerShape(18.dp))) }
        }
        Column(Modifier.padding(top = 60.dp).fillMaxWidth().background(c.s1, RoundedCornerShape(24.dp)).border(1.dp, c.accLine, RoundedCornerShape(24.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            IconTile(R.drawable.ic_lock)
            Text(stringResource(R.string.community_locked_title), style = BType.display(22, 1.2f), color = c.ink, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.community_locked_body), style = BType.body(14, lineHeight = 1.5f), color = c.mut)
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Bar(streak.coerceIn(0, 7) / 7f, c.acc, 6.dp)
                Text(stringResource(R.string.community_locked_progress, streak.coerceAtMost(7)), style = BType.label(12f, 0f), color = c.ink2)
            }
            Row(Modifier.padding(top = 4.dp).fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onPreview),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                Icon(painterResource(R.drawable.ic_visibility), null, tint = c.ink, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.community_preview), style = BType.body(14, FontWeight.Medium), color = c.ink)
            }
        }
    }
}

@Composable
private fun IconTile(icon: Int) {
    val c = LocalBridgeColors.current
    Box(Modifier.size(48.dp).background(c.accTint, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, tint = c.acc, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun Bar(fraction: Float, color: Color, height: Dp) {
    val c = LocalBridgeColors.current
    Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(c.track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).background(color))
    }
}

@Composable
private fun PreviewBanner(onClose: () -> Unit) {
    val c = LocalBridgeColors.current
    Row(Modifier.fillMaxWidth().background(c.accTint, RoundedCornerShape(14.dp)).border(1.dp, c.accLine, RoundedCornerShape(14.dp)).padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(painterResource(R.drawable.ic_visibility), null, tint = c.accInk, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.community_preview_banner), style = BType.body(13, lineHeight = 1.4f), color = c.accInk, modifier = Modifier.weight(1f))
        Box(Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onClose), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.close), style = BType.body(12, FontWeight.SemiBold), color = c.accInk,
                modifier = Modifier.border(1.dp, c.accLine, RoundedCornerShape(18.dp)).padding(horizontal = 12.dp, vertical = 9.dp))
        }
    }
}

@Composable
private fun Segments(tab: CommunityTab, onChange: (CommunityTab) -> Unit) {
    val c = LocalBridgeColors.current
    Row(Modifier.fillMaxWidth().background(c.s0, RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(CommunityTab.Feed to R.string.seg_feed, CommunityTab.Alliance to R.string.seg_alliance).forEach { (t, label) ->
            val on = t == tab
            Box(Modifier.weight(1f).heightIn(min = 40.dp).background(if (on) c.acc else Color.Transparent, RoundedCornerShape(10.dp)).clickable(role = Role.Tab) { onChange(t) }
                .semantics { selected = on }, contentAlignment = Alignment.Center) {
                Text(stringResource(label), style = BType.body(14, FontWeight.SemiBold), color = if (on) c.onAcc else c.mut)
            }
        }
    }
}

// --- Feed ---

@Composable
private fun Feed(ui: CommunityUi, actions: CommunityActions, label: String, now: Long) {
    val c = LocalBridgeColors.current
    val posts = if (ui.previewing) samplePosts(now) else ui.community?.posts.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            ui.feedError && posts.isEmpty() -> ErrorCard(actions.retry)
            ui.loading && posts.isEmpty() -> repeat(3) { Box(Modifier.fillMaxWidth().height(112.dp).background(c.s1, RoundedCornerShape(18.dp)).clearAndSetSemantics { }) }
            posts.isEmpty() -> EmptyCard(stringResource(R.string.feed_empty_title), stringResource(R.string.feed_empty_body))
            else -> {
                if (ui.community?.widened == true && !ui.previewing) Text(stringResource(R.string.feed_widened), style = BType.body(12, lineHeight = 1.4f), color = c.mut)
                posts.forEach { PostCard(it, label, now, ui.previewing, actions) }
            }
        }
        Text(stringResource(R.string.feed_footer), style = BType.body(12), color = c.mut2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(6.dp))
    }
}

@Composable
private fun samplePosts(now: Long) = listOf(
    PostDto("s1", name = "Jonas", kind = "streak", value = 30, createdAt = now - 2 * HOUR, reactions = ReactionCounts(12, 4, 6)),
    PostDto("s2", name = "Priya", kind = "day_complete", createdAt = now - 4 * HOUR, reactions = ReactionCounts(8, 11, 2), reacted = listOf("keepGoing")),
    PostDto("s3", name = "Leo", kind = "milestone", value = 50, createdAt = now - 26 * HOUR, reactions = ReactionCounts(5, 2, 7)),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PostCard(p: PostDto, label: String, now: Long, sample: Boolean, actions: CommunityActions) {
    val c = LocalBridgeColors.current
    val what = when (p.kind) {
        "streak" -> stringResource(R.string.post_streak, p.value ?: 0)
        "milestone" -> stringResource(R.string.post_milestone, p.value ?: 0)
        else -> stringResource(R.string.post_day_complete)
    }
    Column(Modifier.fillMaxWidth().background(c.s1, RoundedCornerShape(18.dp)).border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Row(Modifier.weight(1f).semantics(mergeDescendants = true) { }, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Avatar(p.name, 40.dp, c.accInk)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(buildAnnotatedString { withStyle(SpanStyle(color = c.ink, fontWeight = FontWeight.SemiBold)) { append(p.name) }; append(" "); append(what) }, style = BType.body(14, lineHeight = 1.4f), color = c.ink2)
                    Text("${stringResource(R.string.feed_community, label)} · ${ago(p.createdAt, now)}", style = BType.body(12), color = c.mut)
                }
                if (p.kind == "streak" || p.kind == "milestone") Column(horizontalAlignment = Alignment.End) {
                    Text(if (p.kind == "milestone") "${p.value}%" else "${p.value}", style = BType.display(24, 1f), color = c.accInk)
                    Text(stringResource(if (p.kind == "milestone") R.string.post_big_across else R.string.post_big_days), style = BType.label(9f, 0.12f), color = c.mut)
                }
            }
            // Report / block, tucked into the corner so the reactions keep one line
            if (!p.mine && !sample) Box(Modifier.offset(x = 12.dp, y = (-12).dp).size(44.dp).clickable(role = Role.Button, onClickLabel = stringResource(R.string.post_options)) { actions.postMenu(p) }, contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.post_options), tint = c.mut, modifier = Modifier.size(18.dp))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(Reaction.Respect to (R.string.react_respect to p.reactions.respect), Reaction.KeepGoing to (R.string.react_keep_going to p.reactions.keepGoing),
                Reaction.Inspired to (R.string.react_inspired to p.reactions.inspired)).forEach { (r, v) -> ReactionChip(stringResource(v.first), v.second, r.wire in p.reacted) { actions.react(p, r) } }
        }
    }
}

@Composable
private fun ReactionChip(label: String, n: Int, on: Boolean, onClick: () -> Unit) {
    val c = LocalBridgeColors.current
    val a11y = stringResource(R.string.react_a11y, label, n)
    // 36dp chip inside a 44dp touch target
    Box(Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) { contentDescription = a11y; selected = on }, contentAlignment = Alignment.Center) {
        Row(Modifier.heightIn(min = 36.dp).background(if (on) c.accTint else Color.Transparent, RoundedCornerShape(18.dp)).border(1.dp, if (on) c.acc else c.line, RoundedCornerShape(18.dp)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = BType.body(12, FontWeight.Medium), color = if (on) c.accInk else c.ink2, maxLines = 1)
            Text("$n", style = BType.label(12f, 0f), color = if (on) c.accInk else c.ink2)
        }
    }
}

@Composable
fun Avatar(name: String?, size: Dp, ink: Color, modifier: Modifier = Modifier) {
    val c = LocalBridgeColors.current
    Box(modifier.size(size).background(c.av, CircleShape), contentAlignment = Alignment.Center) {
        Text(name?.take(1)?.uppercase().orEmpty(), style = BType.display((size.value * .38f).toInt(), 1f), color = ink)
    }
}

@Composable
private fun ErrorCard(onRetry: () -> Unit) {
    val c = LocalBridgeColors.current
    Column(Modifier.fillMaxWidth().background(c.s1, RoundedCornerShape(18.dp)).border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.feed_error), style = BType.body(14, FontWeight.Medium), color = c.ink)
        Text(stringResource(R.string.retry), style = BType.body(14, FontWeight.SemiBold), color = c.ink,
            modifier = Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onRetry).padding(horizontal = 18.dp, vertical = 12.dp))
    }
}

@Composable
private fun EmptyCard(title: String, body: String) {
    val c = LocalBridgeColors.current
    Column(Modifier.fillMaxWidth().border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(vertical = 28.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = BType.display(16), color = c.ink)
        Text(body, style = BType.body(13, lineHeight = 1.45f), color = c.mut, textAlign = TextAlign.Center)
    }
}

// --- Alliances ---

@Composable
private fun Alliances(ui: CommunityUi, actions: CommunityActions, label: String, today: LocalDate, now: Long) {
    val c = LocalBridgeColors.current
    val sample = ui.previewing && ui.alliances.isEmpty()
    val ally = if (sample) sampleAlliance(today, now) else ui.selected
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (ally == null) {
            Column(Modifier.fillMaxWidth().background(c.s1, RoundedCornerShape(22.dp)).border(1.dp, c.line, RoundedCornerShape(22.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                IconTile(R.drawable.ic_shield)
                Text(stringResource(R.string.ally_find_title), style = BType.display(21, 1.2f), color = c.ink, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.ally_find_body), style = BType.body(14, lineHeight = 1.5f), color = c.mut)
                Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FillButton(stringResource(R.string.ally_create), actions.create, height = 50.dp)
                    OutlineButton(stringResource(R.string.ally_join_code), actions.join)
                }
            }
            return@Column
        }
        if (ui.alliances.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.ally_chips, label.uppercase(Locale.getDefault()), ui.alliances.size), style = BType.label(10f, 0.14f), color = c.mut)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ui.alliances.forEach { a ->
                    val on = a.id == ally.id
                    Chip(a.name, if (a.isShared) R.drawable.ic_assignment else R.drawable.ic_shield, on) { actions.select(a.id) }
                }
                Chip(stringResource(R.string.ally_new), R.drawable.ic_add, dashed = true, onClick = actions.create)
                Chip(stringResource(R.string.ally_join), R.drawable.ic_key, dashed = true, onClick = actions.join)
            }
        }
        if (sample) Text(stringResource(R.string.ally_sample), style = BType.label(10f, 0.14f), color = c.accInk)
        AllianceCard(ally, ui, actions, sample, today)
        if (!sample) {
            GameCard(ally, ui.games[ally.id], label, { actions.playGame(ally) }, { actions.nudge(ally) })
            ui.ratings[ally.id]?.let { RatingCard(it) { actions.openBoard(ally) } }
        }
        if (sample) SampleTiles()
        Text(stringResource(R.string.ally_feed), style = BType.label(11f, 0.14f), color = c.mut, modifier = Modifier.padding(top = 4.dp).semantics { heading() })
        if (ally.feed.isEmpty()) Text(stringResource(R.string.ally_feed_empty), style = BType.body(13), color = c.mut)
        ally.feed.forEach { FeedRow(it, now) }
    }
}

@Composable
private fun sampleAlliance(today: LocalDate, now: Long) = AllianceDto(
    id = "sample", name = "Dawn Runners", planMode = "own", inviteCode = "DAWN-4821", startDate = today.minusDays(20).toString(), streak = 14, memberCount = 6,
    members = listOf("Sam", "Mia", "Ana", "Rui", "Tom", "Kai").mapIndexed { i, n -> MemberDto("m$i", n, if (i == 0) "owner" else "member") },
    feed = listOf(AllianceFeedDto("Sam", "completed", stringResource(R.string.sample_task_long), now - HOUR), AllianceFeedDto("Ana", "completed", null, now - 3 * HOUR),
        AllianceFeedDto(null, "completed", stringResource(R.string.sample_task_easy), now - 6 * HOUR)),
)

@Composable
private fun Chip(text: String, icon: Int, on: Boolean = false, dashed: Boolean = false, onClick: () -> Unit) {
    val c = LocalBridgeColors.current
    val shape = RoundedCornerShape(20.dp)
    Row(Modifier.heightIn(min = 44.dp).clip(shape).clickable(role = Role.Tab, onClick = onClick).semantics { selected = on }, verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.heightIn(min = 40.dp).background(if (on) c.acc else if (dashed) Color.Transparent else c.s1, shape)
            .then(if (dashed) Modifier.dashed(c.line2, 20.dp) else Modifier.border(1.dp, if (on) c.acc else c.line, shape)).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(icon), null, tint = if (on) c.onAcc else c.ink2, modifier = Modifier.size(16.dp))
            Text(text, style = BType.body(13, FontWeight.Medium), color = if (on) c.onAcc else c.ink2, maxLines = 1)
        }
    }
}

/** 1dp dashed outline (Compose has no dashed border). */
fun Modifier.dashed(color: Color, radius: Dp) = drawBehind {
    val r = radius.toPx()
    drawRoundRect(color, cornerRadius = CornerRadius(r, r), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))))
}

@Composable
private fun AllianceCard(a: AllianceDto, ui: CommunityUi, actions: CommunityActions, sample: Boolean, today: LocalDate) {
    val c = LocalBridgeColors.current
    Column(Modifier.fillMaxWidth().background(c.s1, RoundedCornerShape(22.dp)).border(1.dp, c.line, RoundedCornerShape(22.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(a.name, style = BType.display(22), color = c.ink, modifier = Modifier.semantics { heading() })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(painterResource(R.drawable.ic_lock), null, tint = c.mut, modifier = Modifier.size(13.dp))
                    Text(stringResource(R.string.ally_invite_only), style = BType.label(10f, 0.12f), color = c.mut)
                }
            }
            Box(Modifier.heightIn(min = 44.dp).clickable(role = Role.Button) { if (sample) actions.sampleInvite() else actions.invite(a) }, contentAlignment = Alignment.Center) {
                Row(Modifier.heightIn(min = 40.dp).border(1.dp, c.accLine, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(painterResource(R.drawable.ic_link), null, tint = c.accInk, modifier = Modifier.size(17.dp))
                    Text(stringResource(R.string.ally_invite), style = BType.body(13, FontWeight.SemiBold), color = c.accInk)
                }
            }
            if (!sample) Box(Modifier.size(44.dp).clickable(role = Role.Button) { actions.options(a) }, contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.ally_options), tint = c.mut, modifier = Modifier.size(20.dp))
            }
        }
        a.shared?.let { SharedPanel(a, it, ui, actions, today) }
        FillButton(stringResource(R.string.ally_open_chat), { if (!sample) actions.openChat(a) }, icon = R.drawable.ic_forum)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.ally_streak), style = BType.label(9.5f, 0.12f), color = c.mut)
                Text(buildAnnotatedString { append("${a.streak}"); withStyle(SpanStyle(fontSize = 13.sp, color = c.mut, fontWeight = FontWeight.Normal, fontFamily = app.thebridge.ui.theme.Geist)) { append(" " + stringResource(R.string.ally_days)) } },
                    style = BType.display(28, 1f), color = c.accInk)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.ally_members), style = BType.label(9.5f, 0.12f), color = c.mut)
                Text(stringResource(R.string.ally_members_count, a.memberCount, a.cap), style = BType.display(28, 1f), color = c.ink)
            }
        }
        MemberStack(a.members)
    }
}

@Composable
private fun MemberStack(members: List<MemberDto>) {
    val c = LocalBridgeColors.current
    val awayLabel = members.filter { it.away }.map { stringResource(R.string.ally_member_away, it.name) }
    val a11y = (members.filterNot { it.away }.map { it.name } + awayLabel).joinToString(", ")
    // Overlapping 34dp circles with a 2dp ring in the card colour
    Row(Modifier.semantics { contentDescription = a11y }, horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
        members.take(10).forEach { m ->
            Box(Modifier.size(34.dp).background(c.s1, CircleShape).padding(2.dp)) {
                Avatar(m.name, 30.dp, c.ink2, Modifier.alpha(if (m.away) .45f else 1f))
            }
        }
    }
}

@Composable
private fun SharedPanel(a: AllianceDto, sh: app.thebridge.data.remote.SharedPlanDto, ui: CommunityUi, actions: CommunityActions, today: LocalDate) {
    val c = LocalBridgeColors.current
    val start = runCatching { LocalDate.parse(a.startDate) }.getOrDefault(today)
    val deadline = runCatching { LocalDate.parse(sh.deadline) }.getOrDefault(today)
    val groupTotal = (ChronoUnit.DAYS.between(start, deadline) + 1).toInt().coerceAtLeast(1)
    val groupDay = (ChronoUnit.DAYS.between(start, today) + 1).toInt().coerceIn(1, groupTotal)
    val me = ui.goal?.progress
    val myDay = me?.day ?: groupDay
    val myTotal = me?.total ?: groupTotal
    val late = !sh.isLead && myDay < groupDay
    val dateFmt = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
    Column(Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(16.dp)).border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(R.drawable.ic_assignment), null, tint = c.accInk, modifier = Modifier.size(15.dp))
                Text(stringResource(R.string.ally_shared_plan), style = BType.label(10f, 0.12f), color = c.accInk, maxLines = 1)
            }
            Text(if (sh.isLead) stringResource(R.string.ally_set_by_you) else stringResource(R.string.ally_set_by, sh.lead), style = BType.body(12, FontWeight.Medium), color = c.ink2, textAlign = TextAlign.End)
        }
        Text(sh.goal, style = BType.body(14, FontWeight.Medium, 1.45f), color = c.ink)
        Text(stringResource(R.string.ally_shared_meta, deadline.format(dateFmt).uppercase(Locale.getDefault()), ui.goal?.tasks?.size ?: sh.me.total), style = BType.label(10f, 0.1f), color = c.mut)
        ProgressLine(stringResource(R.string.ally_you), stringResource(R.string.ally_day_of, myDay, myTotal), (myDay.toFloat() / myTotal).coerceAtLeast(.02f), c.acc)
        if (late) ProgressLine(stringResource(R.string.ally_group), stringResource(R.string.ally_day_of, groupDay, groupTotal), groupDay.toFloat() / groupTotal, c.ink2)
        Text(when { sh.isLead -> stringResource(R.string.ally_note_lead); late -> stringResource(R.string.ally_note_late, groupDay - myDay); else -> stringResource(R.string.ally_note_member, sh.lead) },
            style = BType.body(12, lineHeight = 1.45f).copy(fontSize = 12.5.sp), color = c.mut)
        if (sh.isLead && a.memberCount > 1) Text(stringResource(R.string.ally_apply_now), style = BType.body(13, FontWeight.SemiBold), color = c.ink,
            modifier = Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp)).clickable(role = Role.Button) { actions.applyNow(a) }.padding(horizontal = 14.dp, vertical = 12.dp))
    }
}

@Composable
private fun ProgressLine(who: String, value: String, fraction: Float, color: Color) {
    val c = LocalBridgeColors.current
    Column(Modifier.semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(who, style = BType.body(13, FontWeight.Medium), color = c.ink)
            Text(value, style = BType.label(12f, 0f), color = c.ink2)
        }
        Bar(fraction, color, 5.dp)
    }
}

@Composable
private fun SampleTiles() {
    val c = LocalBridgeColors.current
    val tiles = listOf(
        Triple(R.drawable.ic_fire, R.string.sample_tile_streak, R.string.sample_tile_streak_sub), Triple(R.drawable.ic_lock, R.string.sample_tile_private, R.string.sample_tile_private_sub),
        Triple(R.drawable.ic_forum, R.string.sample_tile_feed, R.string.sample_tile_feed_sub), Triple(R.drawable.ic_visibility, R.string.sample_tile_choose, R.string.sample_tile_choose_sub),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEachIndexed { row, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEachIndexed { i, (icon, title, sub) ->
                    Column(Modifier.weight(1f).background(c.tiles[row * 2 + i], RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(painterResource(icon), null, tint = c.acc, modifier = Modifier.size(20.dp))
                        Text(stringResource(title), style = BType.body(13, FontWeight.SemiBold), color = c.ink)
                        Text(stringResource(sub), style = BType.body(12, lineHeight = 1.4f), color = c.mut)
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedRow(f: AllianceFeedDto, now: Long) {
    val c = LocalBridgeColors.current
    val who = f.name ?: stringResource(R.string.ally_you_name)
    val what = when { f.what == "joined" -> stringResource(R.string.ally_feed_joined); f.title != null -> stringResource(R.string.ally_feed_completed, f.title); else -> stringResource(R.string.ally_feed_completed_task) }
    Row(Modifier.fillMaxWidth().background(c.s1, RoundedCornerShape(16.dp)).border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp).semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Avatar(who, 34.dp, c.accInk)
        Text(buildAnnotatedString { withStyle(SpanStyle(color = c.ink, fontWeight = FontWeight.SemiBold)) { append(who) }; append(" "); append(what) }, style = BType.body(14, lineHeight = 1.4f), color = c.ink2, modifier = Modifier.weight(1f))
        Text(ago(f.at, now), style = BType.label(11f, 0f), color = c.mut2)
    }
}

@Composable
fun FillButton(text: String, onClick: () -> Unit, icon: Int? = null, height: Dp = 48.dp, enabled: Boolean = true) {
    val c = LocalBridgeColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = height).clip(RoundedCornerShape(14.dp)).background(c.acc.copy(alpha = if (enabled) 1f else .5f)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        if (icon != null) Icon(painterResource(icon), null, tint = c.onAcc, modifier = Modifier.size(19.dp))
        Text(text, style = BType.body(if (height > 48.dp) 15 else 14, FontWeight.SemiBold), color = c.onAcc, textAlign = TextAlign.Center)
    }
}

@Composable
fun OutlineButton(text: String, onClick: () -> Unit) {
    val c = LocalBridgeColors.current
    Box(Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClick).padding(10.dp), contentAlignment = Alignment.Center) {
        Text(text, style = BType.body(15, FontWeight.Medium), color = c.ink, textAlign = TextAlign.Center)
    }
}

/** "now", "5m", "3h", "2d". */
@Composable
fun ago(at: Long, now: Long): String {
    val m = ((now - at) / 60_000).coerceAtLeast(0)
    return when { m < 1 -> stringResource(R.string.time_now); m < 60 -> stringResource(R.string.time_minutes, m.toInt()); m < 60 * 24 -> stringResource(R.string.time_hours, (m / 60).toInt()); else -> stringResource(R.string.time_days, (m / 1440).toInt()) }
}

private const val HOUR = 3_600_000L
