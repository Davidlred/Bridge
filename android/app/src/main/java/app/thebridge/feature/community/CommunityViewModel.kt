package app.thebridge.feature.community

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.thebridge.R
import app.thebridge.data.GoalRepository
import app.thebridge.data.GoalState
import app.thebridge.data.NetworkMonitor
import app.thebridge.data.Reaction
import app.thebridge.data.ShareLevel
import app.thebridge.data.SocialRepository
import app.thebridge.data.remote.AllianceDto
import app.thebridge.data.remote.ApiException
import app.thebridge.data.remote.CommunityDto
import app.thebridge.data.remote.InvitePreviewDto
import app.thebridge.data.remote.OfflineException
import app.thebridge.data.remote.PostDto
import app.thebridge.nav.Community
import app.thebridge.ui.Toaster
import app.thebridge.ui.components.ToastKind
import app.thebridge.ui.components.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class CommunityTab { Feed, Alliance }

/** Sheets over the Community screen. Form state lives here so it survives rotation. */
sealed interface CommunitySheet {
    data class Create(val name: String = "", val shared: Boolean = false, val error: UiText? = null, val busy: Boolean = false) : CommunitySheet
    data class Join(val code: String = "", val error: UiText? = null, val busy: Boolean = false, val preview: InvitePreviewDto? = null) : CommunitySheet
    data class PostMenu(val post: PostDto) : CommunitySheet
    data class Leave(val alliance: AllianceDto) : CommunitySheet
}

data class CommunityUi(
    val goal: GoalState? = null,
    val community: CommunityDto? = null,
    val loading: Boolean = true,
    val feedError: Boolean = false,
    val previewing: Boolean = false,
    val tab: CommunityTab = CommunityTab.Feed,
    val share: ShareLevel = ShareLevel.Streaks,
    /** This goal's alliances. */
    val alliances: List<AllianceDto> = emptyList(),
    val selectedId: String? = null,
    val sheet: CommunitySheet? = null,
    /** Game card and rating per alliance (online only; absent while loading or offline). */
    val games: Map<String, app.thebridge.data.remote.GameStatusDto> = emptyMap(),
    val ratings: Map<String, app.thebridge.data.remote.RatingDto> = emptyMap(),
) {
    /** Before the first response, the device's own streak decides (the server confirms it). */
    val unlocked: Boolean get() = community?.unlocked ?: ((goal?.progress?.streak ?: 0) >= 7)
    val streak: Int get() = community?.streak ?: goal?.progress?.streak ?: 0
    val locked: Boolean get() = !unlocked && !previewing
    val selected: AllianceDto? get() = alliances.find { it.id == selectedId } ?: alliances.firstOrNull()
}

@HiltViewModel
class CommunityViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val social: SocialRepository,
    goals: GoalRepository,
    network: NetworkMonitor,
    private val toaster: Toaster,
    private val analytics: app.thebridge.data.Analytics,
) : ViewModel() {
    private val route = saved.toRoute<Community>()
    val goalId = route.goalId
    private val local = MutableStateFlow(Local())

    private data class Local(
        val loading: Boolean = true, val feedError: Boolean = false, val previewing: Boolean = false, val tab: CommunityTab = CommunityTab.Feed,
        val selectedId: String? = null, val sheet: CommunitySheet? = null,
        val games: Map<String, app.thebridge.data.remote.GameStatusDto> = emptyMap(), val ratings: Map<String, app.thebridge.data.remote.RatingDto> = emptyMap(),
    )

    val state: StateFlow<CommunityUi> = combine(goals.activeGoals(), social.community(goalId), social.alliances(), social.share, local) { gs, c, all, share, l ->
        CommunityUi(
            goal = gs.find { it.goal.id == goalId }, community = c, loading = l.loading && c == null, feedError = l.feedError, previewing = l.previewing,
            tab = l.tab, share = share, alliances = all.filter { it.goalId == goalId }, selectedId = l.selectedId, sheet = l.sheet, games = l.games, ratings = l.ratings,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CommunityUi())

    init {
        refresh()
        // An invite link opens the join sheet with its code checked straight away.
        route.code?.let { code -> local.update { it.copy(tab = CommunityTab.Alliance, sheet = CommunitySheet.Join(code.uppercase())) }; join() }
        viewModelScope.launch { network.online.collect { if (it && state.value.feedError) refresh() } }
    }

    fun refresh() = viewModelScope.launch {
        local.update { it.copy(feedError = false) }
        try {
            social.refreshCommunity(goalId)
            runCatching { social.refreshAlliances() }
            local.update { it.copy(loading = false) }
            loadGame()
        } catch (e: Exception) {
            local.update { it.copy(loading = false, feedError = true) }
        }
    }

    fun showTab(t: CommunityTab) = local.update { it.copy(tab = t) }
    fun startPreview() = local.update { it.copy(previewing = true, tab = CommunityTab.Feed) }
    fun endPreview() = local.update { it.copy(previewing = false) }
    fun select(id: String) { local.update { it.copy(selectedId = id) }; loadGame() }

    /** Game card and rating for the alliance on screen. */
    fun loadGame() = viewModelScope.launch {
        val a = state.value.selected ?: social.alliances().first().firstOrNull { it.goalId == goalId } ?: return@launch
        runCatching { social.gameStatus(a.id) }.onSuccess { s -> local.update { it.copy(games = it.games + (a.id to s)) } }
        runCatching { social.rating(a.id) }.onSuccess { r -> local.update { it.copy(ratings = it.ratings + (a.id to r)) } }
    }

    fun nudge(a: AllianceDto) = viewModelScope.launch {
        try { toaster.show(UiText.Res(if (social.nudge(a.id) > 0) R.string.toast_nudged else R.string.toast_nudged_none), ToastKind.Ok) }
        catch (e: Exception) { toaster.show(offlineOr(e), ToastKind.Warn) }
    }

    fun cycleShare() = viewModelScope.launch {
        social.setShare(state.value.share.next())
        toaster.show(UiText.Res(R.string.toast_share_updated), ToastKind.Ok)
    }

    fun react(post: PostDto, r: Reaction) = viewModelScope.launch {
        if (state.value.previewing) return@launch
        try { social.react(goalId, post.id, r, r.wire !in post.reacted) } catch (e: Exception) { toaster.show(offlineOr(e), ToastKind.Warn) }
    }

    fun openPostMenu(post: PostDto) = local.update { it.copy(sheet = CommunitySheet.PostMenu(post)) }
    fun closeSheet() = local.update { it.copy(sheet = null) }

    fun report(post: PostDto, reason: String) = viewModelScope.launch {
        closeSheet()
        try { social.reportPost(goalId, post.id, reason); toaster.show(UiText.Res(R.string.toast_reported), ToastKind.Ok) }
        catch (e: Exception) { toaster.show(offlineOr(e), ToastKind.Warn) }
    }

    fun block(post: PostDto) = viewModelScope.launch {
        closeSheet()
        val userId = post.userId ?: return@launch
        try { social.block(userId, goalId); toaster.show(UiText.Res(R.string.toast_blocked, listOf(post.name)), ToastKind.Ok) }
        catch (e: Exception) { toaster.show(offlineOr(e), ToastKind.Warn) }
    }

    // --- Create ---
    /** Alliances need an open Community; the preview only explains them. */
    private fun gated(): Boolean {
        if (!state.value.previewing) return false
        toaster.show(UiText.Res(R.string.toast_preview_only, listOf((7 - state.value.streak).coerceAtLeast(1))))
        return true
    }

    fun openCreate() { if (!gated()) local.update { it.copy(sheet = CommunitySheet.Create()) } }
    fun openJoin() { if (!gated()) local.update { it.copy(sheet = CommunitySheet.Join()) } }

    private inline fun <reified T : CommunitySheet> editSheet(f: (T) -> T) = local.update { l -> (l.sheet as? T)?.let { l.copy(sheet = f(it)) } ?: l }

    fun onName(v: String) = editSheet<CommunitySheet.Create> { it.copy(name = v.take(40), error = null) }
    fun onShared(v: Boolean) = editSheet<CommunitySheet.Create> { it.copy(shared = v) }

    fun create() {
        val s = local.value.sheet as? CommunitySheet.Create ?: return
        if (s.busy) return
        if (s.name.trim().length < 3) return editSheet<CommunitySheet.Create> { it.copy(error = UiText.Res(R.string.create_name_short)) }
        editSheet<CommunitySheet.Create> { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val a = social.create(s.name, if (s.shared) "shared" else "own", goalId)
                local.update { it.copy(sheet = null, tab = CommunityTab.Alliance, selectedId = a.id) }
                analytics.track(app.thebridge.data.Event.AllianceCreated, "mode" to a.planMode)
                toaster.show(UiText.Res(if (s.shared) R.string.toast_created_shared else R.string.toast_created), ToastKind.Ok)
            } catch (e: Exception) {
                val err = when ((e as? ApiException)?.code) { "name_not_allowed" -> UiText.Res(R.string.create_name_blocked); else -> offlineOr(e) }
                editSheet<CommunitySheet.Create> { it.copy(busy = false, error = err) }
            }
        }
    }

    // --- Join: a code; a shared plan shows its preview before joining ---
    fun onCode(v: String) = editSheet<CommunitySheet.Join> { it.copy(code = v.uppercase().filter { c -> c.isLetterOrDigit() || c == '-' }.take(9), error = null) }
    fun backToCode() = editSheet<CommunitySheet.Join> { CommunitySheet.Join() }

    fun join() {
        val s = local.value.sheet as? CommunitySheet.Join ?: return
        if (s.busy) return
        val code = s.code.trim()
        if (!CODE.matches(code)) return editSheet<CommunitySheet.Join> { it.copy(error = UiText.Res(R.string.join_bad_format)) }
        editSheet<CommunitySheet.Join> { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                if (s.preview == null) {
                    val p = social.preview(code)
                    // Shared plans show what joining adds; own-plan alliances join with this goal at once.
                    if (p.planMode == "shared") { editSheet<CommunitySheet.Join> { it.copy(busy = false, preview = p) }; return@launch }
                }
                val a = social.join(code, goalId.takeIf { s.preview == null })
                analytics.track(app.thebridge.data.Event.AllianceJoined, "mode" to a.planMode, "late_days" to ((s.preview?.pace?.groupDay ?: 1) - 1))
                local.update { it.copy(sheet = null, tab = CommunityTab.Alliance, selectedId = a.id) }
                toaster.show(if (a.isShared) UiText.Res(R.string.toast_joined_shared, listOf(a.name, s.preview?.label ?: a.name)) else UiText.Res(R.string.toast_joined, listOf(a.name)), ToastKind.Ok)
            } catch (e: Exception) {
                editSheet<CommunitySheet.Join> { it.copy(busy = false, error = joinError(e)) }
            }
        }
    }

    private fun joinError(e: Exception): UiText {
        val api = e as? ApiException ?: return offlineOr(e)
        return when (api.code) {
            "invalid_code_format" -> UiText.Res(R.string.join_bad_format)
            "invalid_code" -> UiText.Res(R.string.join_invalid)
            "already_member" -> UiText.Res(R.string.join_already, listOf(api.body["name"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()))
            "alliance_full" -> UiText.Res(R.string.join_full, listOf(api.body["cap"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() } ?: 15))
            "plan_ended" -> UiText.Res(R.string.join_ended)
            "goal_limit" -> UiText.Res(R.string.join_goal_limit)
            else -> UiText.Res(R.string.error_generic)
        }
    }

    // --- Alliance actions ---
    fun askLeave(a: AllianceDto) = local.update { it.copy(sheet = CommunitySheet.Leave(a)) }

    fun leave(a: AllianceDto) = viewModelScope.launch {
        closeSheet()
        try { social.leave(a.id); local.update { it.copy(selectedId = null) }; toaster.show(UiText.Res(R.string.toast_left, listOf(a.name)), ToastKind.Ok) }
        catch (e: Exception) { toaster.show(offlineOr(e), ToastKind.Warn) }
    }

    fun applyNow(a: AllianceDto) = viewModelScope.launch {
        try { social.applyPlanNow(a.id); toaster.show(UiText.Res(R.string.toast_applied), ToastKind.Ok) }
        catch (e: Exception) { toaster.show(offlineOr(e), ToastKind.Warn) }
    }

    fun sampleInvite() = toaster.show(UiText.Res(R.string.toast_sample_invite))

    private fun offlineOr(e: Exception) = UiText.Res(if (e is OfflineException) R.string.error_offline else R.string.error_generic)

    private companion object { val CODE = Regex("^[A-Z]{4}-\\d{4}$") }
}
