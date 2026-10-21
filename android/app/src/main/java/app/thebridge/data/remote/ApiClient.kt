package app.thebridge.data.remote

import app.thebridge.BuildConfig
import app.thebridge.data.session.TokenVault
import app.thebridge.data.session.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/** The server answered with an error. [code] is the machine code from the body (e.g. `email_taken`). */
class ApiException(val status: Int, val code: String, val body: JsonObject) : Exception("$status $code") {
    val provider: String? get() = body["provider"]?.jsonPrimitive?.content
    val retryAfterSec: Int? get() = runCatching { body["retryAfterSec"]?.jsonPrimitive?.int }.getOrNull()
}
/** Refresh token rejected: the user has to sign in again. Local data and the outbox stay untouched. */
class UnauthorizedException : Exception()
/** No connection, DNS failure, timeout. */
class OfflineException(cause: IOException) : Exception(cause)
/** A coach reply stopped part way. The partial text is kept and the user can retry. */
class StreamInterrupted : Exception()

@Serializable data class UserDto(val id: String, val email: String? = null, val displayName: String, val coachName: String = "Arc", val coachStyle: String = "balanced", val createdAt: Long = 0, val photoUrl: String? = null)
/** [isNew]: the account was just created, so the user goes to goal onboarding. */
@Serializable data class AuthResponse(val user: UserDto, val accessToken: String, val refreshToken: String, val isNew: Boolean = false)
@Serializable private data class MeResponse(val user: UserDto)

@Serializable data class Feasibility(val verdict: String, val reason: String, val suggestedDeadline: String? = null)
@Serializable data class PlanTaskDto(val title: String, val priority: String, val timeOfDay: String, val minutes: Int, val rationale: String)
@Serializable data class PlanDto(val needsClarification: String? = null, val label: String, val goal: String, val category: String? = null, val feasibility: Feasibility, val tasks: List<PlanTaskDto>)
@Serializable private data class PlanResponse(val plan: PlanDto)
@Serializable private data class RealityResponse(val feasibility: Feasibility)
@Serializable data class GoalImageDto(val kind: String, val status: String, val url: String? = null, val withFace: Boolean = false)
@Serializable private data class ImagesResponse(val images: List<GoalImageDto>)
@Serializable private data class PhotoResponse(val photoUrl: String)
@Serializable private data class RefreshResponse(val accessToken: String, val refreshToken: String)

@Serializable data class MutationDto(val id: String, val entity: String, val entityId: String, val op: String, val patch: JsonElement, val clientTs: Long)
@Serializable data class PushResponse(val accepted: List<String> = emptyList(), val rejected: Map<String, String> = emptyMap())
@Serializable data class GoalDto(val id: String, val label: String, val statement: String, val startDate: String, val deadline: String, val status: String, val sourceAllianceId: String? = null, val colorIndex: Int = 0, val updatedAt: Long = 0, val routine: String? = null, val realityNote: String? = null, val feasibility: Feasibility? = null, val category: String? = null)
@Serializable data class TaskDto(val id: String, val goalId: String, val title: String, val priority: String, val timeOfDay: String? = null, val minutes: Int? = null, val rationale: String? = null, val notes: String? = null, val origin: String = "user", val sharedTaskId: String? = null, val position: Int = 0, val deletedAt: Long? = null, val updatedAt: Long = 0)
@Serializable data class CompletionDto(val taskId: String, val localDate: String, val completedAt: Long = 0)
@Serializable data class CompletionKey(val taskId: String, val localDate: String)
@Serializable data class Removed(val goals: List<String> = emptyList(), val completions: List<CompletionKey> = emptyList())
@Serializable data class JournalDto(val id: String, val goalId: String? = null, val localDate: String, val mood: Int? = null, val body: String = "", val version: Int = 1, val conflictOf: String? = null, val updatedAt: Long = 0)
@Serializable data class PullResponse(val cursor: Long, val hasMore: Boolean = false, val goals: List<GoalDto> = emptyList(), val tasks: List<TaskDto> = emptyList(), val completions: List<CompletionDto> = emptyList(), val removed: Removed = Removed(), val settings: JsonObject? = null, val journal: List<JournalDto> = emptyList(), val removedJournal: List<String> = emptyList(), val chat: List<ChatDto> = emptyList(), val removedChat: List<String> = emptyList())
@Serializable data class ChatDto(val id: String, val conversation: String, val goalId: String? = null, val role: String, val text: String, val suggestion: SuggestionDto? = null, val status: String = "done", val suggestionState: String? = null, val createdAt: Long = 0)
@Serializable data class SuggestionDto(val title: String, val rationale: String = "", val priority: String = "Medium", val timeOfDay: String? = null)

// --- Social (milestone 4). Names are first names only; the server never sends other people's goal text or tasks. ---
@Serializable data class ReactionCounts(val respect: Int = 0, val keepGoing: Int = 0, val inspired: Int = 0)
@Serializable data class PostDto(val id: String, val userId: String? = null, val mine: Boolean = false, val name: String, val kind: String, val value: Int? = null, val createdAt: Long = 0, val reactions: ReactionCounts = ReactionCounts(), val reacted: List<String> = emptyList())
@Serializable data class CommunityDto(val unlocked: Boolean, val streak: Int = 0, val needed: Int = 7, val widened: Boolean = false, val posts: List<PostDto> = emptyList())
@Serializable data class MemberDto(val userId: String, val name: String, val role: String, val away: Boolean = false, val me: Boolean = false)
@Serializable data class DoneCount(val done: Int = 0, val total: Int = 0)
@Serializable data class SharedPlanDto(val lead: String, val goal: String, val deadline: String, val me: DoneCount = DoneCount(), val group: DoneCount = DoneCount(), val isLead: Boolean = false)
@Serializable data class AllianceFeedDto(val name: String? = null, val what: String, val title: String? = null, val at: Long = 0)
@Serializable data class AllianceDto(
    val id: String, val name: String, val planMode: String, val inviteCode: String, val cap: Int = 15, val startDate: String, val role: String? = null, val goalId: String? = null,
    val streak: Int = 0, val members: List<MemberDto> = emptyList(), val memberCount: Int = 0, val shared: SharedPlanDto? = null, val feed: List<AllianceFeedDto> = emptyList(),
) { val isShared: Boolean get() = planMode == "shared" }
@Serializable data class PreviewTaskDto(val title: String, val priority: String = "Medium", val timeOfDay: String? = null)
@Serializable data class PaceDto(val groupDay: Int, val groupTotal: Int, val yourTotal: Int)
@Serializable data class InvitePreviewDto(
    val id: String, val name: String, val planMode: String, val lead: String, val members: Int, val cap: Int, val spotsLeft: Int,
    val goal: String? = null, val label: String? = null, val deadline: String? = null, val tasks: List<PreviewTaskDto> = emptyList(), val pace: PaceDto? = null,
)
@Serializable data class AllianceMessageDto(val id: String, val clientId: String? = null, val userId: String? = null, val name: String? = null, val mine: Boolean = false, val body: String, val createdAt: Long = 0)
@Serializable private data class AllianceResponse(val alliance: AllianceDto)
@Serializable private data class AlliancesResponse(val alliances: List<AllianceDto>)
@Serializable private data class PreviewResponse(val preview: InvitePreviewDto)
@Serializable private data class MessagesResponse(val messages: List<AllianceMessageDto>)
@Serializable private data class MessageResponse(val message: AllianceMessageDto)

// --- Game Night, rankings, Hall of Fame (milestone 5). Roles only appear in the result. ---
@Serializable data class GamePlayerDto(val userId: String, val name: String, val ready: Boolean = false, val absent: Boolean = false, val voted: Boolean = false,
    val role: String? = null, val actions: Int = 0, @kotlinx.serialization.SerialName("fun") val funPoints: Int = 0)
@Serializable data class ClueDto(val who: String, val text: String)
@Serializable data class GameDto(
    val id: String, val allianceId: String, val ranked: Boolean = false, val phase: String, val endsAt: Long = 0, val players: List<GamePlayerDto> = emptyList(),
    val planks: List<String> = emptyList(), val integrity: Int = 100, val clues: List<ClueDto> = emptyList(),
    val drifterId: String? = null, val ejectedId: String? = null, val buildersWon: Boolean? = null, val tally: Map<String, Int> = emptyMap(), val mvpId: String? = null,
)
@Serializable data class GameStatusDto(val unlocked: Boolean = false, val rankedToday: Boolean = false, val eligible: Int = 0, val finished: Int = 0, val needsPlayers: Boolean = false,
    val waiting: List<String> = emptyList(), val meLeft: Int? = null, val game: GameDto? = null)
@Serializable data class RatingDto(val overall: Int, val tier: String, val rank: Int, val of: Int, val band: String = "", val productivity: Int, val efficiency: Int, @kotlinx.serialization.SerialName("fun") val funScore: Int)
@Serializable data class BoardRowDto(val rank: Int, val name: String, val score: Int, val tier: String, val members: Int, val mine: Boolean = false)
@Serializable data class BoardDto(val category: String = "other", val band: String = "", val rank: Int = 0, val of: Int = 0, val rows: List<BoardRowDto> = emptyList())
@Serializable data class BadgeDto(val key: String, val earned: Boolean, val earnedAt: Long? = null, val progress: Int? = null, val goal: Int? = null)
@Serializable data class BadgesDto(val public: Boolean = false, val badges: List<BadgeDto> = emptyList())
@Serializable private data class GameResponse(val game: GameDto)
@Serializable private data class NudgeResponse(val sent: Int = 0)

// --- Encrypted DMs: the server relays public keys and ciphertext only ---
@Serializable data class DmContactDto(val userId: String, val name: String, val ready: Boolean = false)
@Serializable data class DeviceKeysDto(val deviceId: String, val curve25519: String, val ed25519: String)
@Serializable data class ClaimedKeyDto(val deviceId: String, val keyId: String, val key: String, val fallback: Boolean = false)
@Serializable data class EnvelopeOutDto(val deviceId: String, val type: Int, val body: String)
@Serializable data class EnvelopeDto(val id: String, val from: String, val fromDevice: String, val senderKey: String? = null, val clientId: String, val type: Int, val body: String, val peer: String, val createdAt: Long = 0)
@Serializable private data class ContactsResponse(val contacts: List<DmContactDto>)
@Serializable private data class DevicesResponse(val devices: List<DeviceKeysDto>)
@Serializable private data class ClaimResponse(val keys: List<ClaimedKeyDto>)
@Serializable private data class InboxResponse(val envelopes: List<EnvelopeDto>)
@Serializable private data class KeyCountResponse(val oneTimeKeys: Int)

/** Events of one coach reply. */
sealed interface CoachEvent {
    data class Token(val text: String) : CoachEvent
    data class Suggest(val suggestion: SuggestionDto) : CoachEvent
}

/** All HTTP goes through here. Repositories call it; ViewModels never do. */
@Singleton
class ApiClient @Inject constructor(private val http: OkHttpClient, private val vault: TokenVault, private val network: app.thebridge.data.NetworkMonitor) {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val refreshLock = Mutex()
    /** Coach replies can stream for a while; no read timeout between tokens beyond a minute. */
    /** Plan and Reality Check: the server answers within 75s (model, retries included). */
    private val slowHttp = http.newBuilder().readTimeout(90, java.util.concurrent.TimeUnit.SECONDS).build()
    private val streamHttp = http.newBuilder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
    /** The realtime socket stays open while a social screen is showing; pings keep proxies from closing it. */
    private val socketHttp = http.newBuilder().readTimeout(0, java.util.concurrent.TimeUnit.SECONDS).pingInterval(25, java.util.concurrent.TimeUnit.SECONDS).build()
    private val _upgradeRequired =MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits when the server answers 426: show the blocking "Update The Bridge" screen. */
    val upgradeRequired = _upgradeRequired.asSharedFlow()

    private val tz get() = TimeZone.getDefault().id

    suspend fun signUp(name: String, email: String, password: String, deviceId: String): AuthResponse =
        post("/v1/auth/email/signup", buildJsonObject { put("displayName", name); put("email", email); put("password", password); put("timezone", tz); put("deviceId", deviceId) })
    suspend fun logIn(email: String, password: String, deviceId: String): AuthResponse =
        post("/v1/auth/email/login", buildJsonObject { put("email", email); put("password", password); put("deviceId", deviceId) })
    suspend fun google(idToken: String, nonce: String, deviceId: String): AuthResponse =
        post("/v1/auth/google", buildJsonObject { put("idToken", idToken); put("nonce", nonce); put("timezone", tz); put("deviceId", deviceId) })
    suspend fun forgotPassword(email: String) { call("POST", "/v1/auth/password/forgot", buildJsonObject { put("email", email) }, authed = false) }
    suspend fun logOut() { call("POST", "/v1/auth/logout", JsonObject(emptyMap()), authed = true) }
    suspend fun deleteAccount() { call("DELETE", "/v1/account", null, authed = true) }

    suspend fun updateMe(coachName: String? = null, displayName: String? = null, coachStyle: String? = null): UserDto = json.decodeFromString<MeResponse>(call("PATCH", "/v1/me", buildJsonObject {
        if (coachName != null) put("coachName", coachName); if (displayName != null) put("displayName", displayName); if (coachStyle != null) put("coachStyle", coachStyle)
    }, authed = true)).user

    /**
     * Streams a coach reply (Server-Sent Events). Calls [onEvent] for each token and the optional suggestion.
     * Throws OfflineException / ApiException before the stream starts, or [StreamInterrupted] if it breaks part way.
     */
    suspend fun coach(body: JsonObject, onEvent: suspend (CoachEvent) -> Unit) = withContext(Dispatchers.IO) {
        suspend fun open(bearer: String): okhttp3.Response {
            val req = Request.Builder().url(BuildConfig.API_BASE + "/v1/coach/messages").post(body.toString().toRequestBody(JSON))
                .header("Accept", "text/event-stream").header("X-Platform", "android").header("X-App-Version", BuildConfig.VERSION_NAME)
                .header("Authorization", "Bearer $bearer").build()
            return try { streamHttp.newCall(req).execute() } catch (e: IOException) { throw OfflineException(e) }
        }
        val used = vault.read() ?: throw UnauthorizedException()
        var res = open(used.access)
        if (res.code == 401) { res.close(); call("GET", "/v1/me", null, authed = true); res = open(vault.read()!!.access) }   // refreshes the token
        res.use { r ->
            if (!r.isSuccessful) { Raw(r.code, r.body?.string().orEmpty()).orThrow() }
            val source = r.body!!.source()
            var event = ""; var finished = false
            try {
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    when {
                        line.startsWith("event: ") -> event = line.removePrefix("event: ")
                        line.startsWith("data: ") -> {
                            val data = json.decodeFromString<JsonObject>(line.removePrefix("data: "))
                            when (event) {
                                "token" -> onEvent(CoachEvent.Token(data["text"]!!.jsonPrimitive.content))
                                "suggestion" -> onEvent(CoachEvent.Suggest(json.decodeFromJsonElement(SuggestionDto.serializer(), data)))
                                "done" -> finished = true
                                "error" -> throw StreamInterrupted()
                            }
                        }
                    }
                }
            } catch (e: IOException) { throw StreamInterrupted() }
            if (!finished) throw StreamInterrupted()
        }
    }

    suspend fun me(): UserDto = json.decodeFromString<MeResponse>(call("GET", "/v1/me", null, authed = true)).user

    // --- AI (all model calls happen on the server) ---
    suspend fun plan(goalText: String, deadline: String, today: String, routine: String, existingGoals: Int): PlanDto =
        json.decodeFromString<PlanResponse>(call("POST", "/v1/ai/plan", buildJsonObject {
            put("goalText", goalText); put("deadline", deadline); put("today", today); put("timezone", tz); put("routine", routine)
            put("locale", java.util.Locale.getDefault().toLanguageTag()); put("existingGoals", existingGoals)
        }, authed = true, slow = true)).plan
    suspend fun reality(goalText: String, deadline: String, today: String, routine: String, tasks: List<String>): Feasibility =
        json.decodeFromString<RealityResponse>(call("POST", "/v1/ai/reality", buildJsonObject {
            put("goalText", goalText); put("deadline", deadline); put("today", today); put("routine", routine)
            put("tasks", kotlinx.serialization.json.JsonArray(tasks.take(20).map { kotlinx.serialization.json.JsonPrimitive(it.take(120)) }))
        }, authed = true, slow = true)).feasibility
    suspend fun startImages(goalId: String, goalText: String, routine: String, kinds: List<String>): List<GoalImageDto> =
        json.decodeFromString<ImagesResponse>(call("POST", "/v1/ai/images", buildJsonObject {
            put("goalId", goalId); put("goalText", goalText); put("routine", routine); put("kinds", kotlinx.serialization.json.JsonArray(kinds.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }, authed = true)).images
    suspend fun images(goalId: String): List<GoalImageDto> = json.decodeFromString<ImagesResponse>(call("GET", "/v1/ai/images/$goalId", null, authed = true)).images

    /** The caller has shown the consent text; the header records that the user agreed. */
    suspend fun uploadPhoto(jpeg: ByteArray): String =
        json.decodeFromString<PhotoResponse>(call("PUT", "/v1/me/photo", null, authed = true, raw = jpeg.toRequestBody(JPEG), extraHeaders = mapOf("X-Photo-Consent" to "1"))).photoUrl
    suspend fun deletePhoto() { call("DELETE", "/v1/me/photo", null, authed = true) }

    // --- Community and alliances ---
    suspend fun community(goalId: String): CommunityDto = json.decodeFromString(call("GET", "/v1/community?goalId=$goalId", null, authed = true))
    /** Progress to share. The server checks it against its own completions and the user's sharing setting. */
    suspend fun announce(goalId: String, kind: String, value: Int?) {
        call("POST", "/v1/community/posts", buildJsonObject { put("goalId", goalId); put("kind", kind); if (value != null) put("value", value) }, authed = true)
    }
    suspend fun react(postId: String, kind: String, on: Boolean) { call(if (on) "PUT" else "DELETE", "/v1/community/posts/$postId/reactions/$kind", null, authed = true) }
    suspend fun report(targetKind: String, targetId: String, reason: String) {
        call("POST", "/v1/reports", buildJsonObject { put("targetKind", targetKind); put("targetId", targetId); put("reason", reason) }, authed = true)
    }
    suspend fun block(userId: String) { call("PUT", "/v1/blocks/$userId", null, authed = true) }
    /** Report a person. For DMs, [messages] are what the reporter's device decrypted (the server can't read DMs). */
    suspend fun reportUser(userId: String, reason: String, messages: List<String>) {
        call("POST", "/v1/reports", buildJsonObject {
            put("targetKind", "user"); put("targetId", userId); put("reason", reason)
            put("evidence", buildJsonObject { put("messages", kotlinx.serialization.json.JsonArray(messages.map { kotlinx.serialization.json.JsonPrimitive(it.take(2000)) })) })
        }, authed = true)
    }

    suspend fun alliances(): List<AllianceDto> = json.decodeFromString<AlliancesResponse>(call("GET", "/v1/alliances", null, authed = true)).alliances
    suspend fun createAlliance(name: String, planMode: String, goalId: String): AllianceDto =
        json.decodeFromString<AllianceResponse>(call("POST", "/v1/alliances", buildJsonObject { put("name", name); put("planMode", planMode); put("goalId", goalId) }, authed = true)).alliance
    suspend fun previewAlliance(code: String): InvitePreviewDto = json.decodeFromString<PreviewResponse>(call("GET", "/v1/alliances/preview/$code", null, authed = true)).preview
    suspend fun joinAlliance(code: String, goalId: String?): AllianceDto =
        json.decodeFromString<AllianceResponse>(call("POST", "/v1/alliances/join", buildJsonObject { put("code", code); if (goalId != null) put("goalId", goalId) }, authed = true)).alliance
    suspend fun leaveAlliance(id: String) { call("POST", "/v1/alliances/$id/leave", null, authed = true) }
    suspend fun applyPlanNow(id: String) { call("POST", "/v1/alliances/$id/apply-now", null, authed = true) }
    suspend fun allianceMessages(id: String): List<AllianceMessageDto> = json.decodeFromString<MessagesResponse>(call("GET", "/v1/alliances/$id/messages", null, authed = true)).messages
    suspend fun sendAllianceMessage(id: String, clientId: String, body: String): AllianceMessageDto =
        json.decodeFromString<MessageResponse>(call("POST", "/v1/alliances/$id/messages", buildJsonObject { put("clientId", clientId); put("body", body) }, authed = true)).message

    // --- Game Night, rankings, Hall of Fame ---
    suspend fun gameStatus(allianceId: String): GameStatusDto = json.decodeFromString(call("GET", "/v1/alliances/$allianceId/game", null, authed = true))
    suspend fun openGame(allianceId: String): GameDto = json.decodeFromString<GameResponse>(call("POST", "/v1/alliances/$allianceId/game", null, authed = true)).game
    suspend fun nudge(allianceId: String): Int = json.decodeFromString<NudgeResponse>(call("POST", "/v1/alliances/$allianceId/game/nudge", null, authed = true)).sent
    suspend fun shareGame(allianceId: String, gameId: String) { call("POST", "/v1/alliances/$allianceId/games/$gameId/share", null, authed = true) }
    suspend fun rating(allianceId: String): RatingDto = json.decodeFromString(call("GET", "/v1/alliances/$allianceId/rating", null, authed = true))
    suspend fun rankings(allianceId: String): BoardDto = json.decodeFromString(call("GET", "/v1/alliances/$allianceId/rankings", null, authed = true))
    /** The whole account as JSON ("Download my data"). Never logged or cached. */
    suspend fun exportData(): String = call("GET", "/v1/me/export", null, authed = true)
    /** Entitlements the server honours (from RevenueCat webhooks). */
    suspend fun entitlements(): List<String> =
        (json.decodeFromString<JsonObject>(call("GET", "/v1/me/entitlements", null, authed = true))["entitlements"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.content }.orEmpty()
    suspend fun badges(): BadgesDto = json.decodeFromString(call("GET", "/v1/me/badges", null, authed = true))

    suspend fun registerE2eDevice(deviceId: String, curve25519: String, ed25519: String) {
        call("PUT", "/v1/e2e/devices/$deviceId", buildJsonObject { put("curve25519", curve25519); put("ed25519", ed25519) }, authed = true)
    }
    /** Publishes one-time keys (and a fallback key); returns how many unused one-time keys the server holds. */
    suspend fun publishKeys(deviceId: String, keys: List<Pair<String, String>>, fallback: Pair<String, String>?): Int =
        json.decodeFromString<KeyCountResponse>(call("POST", "/v1/e2e/devices/$deviceId/keys", buildJsonObject {
            put("oneTimeKeys", kotlinx.serialization.json.JsonArray(keys.map { (id, k) -> buildJsonObject { put("id", id); put("key", k) } }))
            if (fallback != null) put("fallback", buildJsonObject { put("id", fallback.first); put("key", fallback.second) })
        }, authed = true)).oneTimeKeys
    suspend fun dmContacts(): List<DmContactDto> = json.decodeFromString<ContactsResponse>(call("GET", "/v1/e2e/contacts", null, authed = true)).contacts
    suspend fun userDevices(userId: String): List<DeviceKeysDto> = json.decodeFromString<DevicesResponse>(call("GET", "/v1/e2e/users/$userId/devices", null, authed = true)).devices
    suspend fun claimKeys(deviceIds: List<String>): List<ClaimedKeyDto> =
        json.decodeFromString<ClaimResponse>(call("POST", "/v1/e2e/claim", buildJsonObject { put("deviceIds", kotlinx.serialization.json.JsonArray(deviceIds.map { kotlinx.serialization.json.JsonPrimitive(it) })) }, authed = true)).keys
    suspend fun sendDm(to: String, fromDevice: String, clientId: String, envelopes: List<EnvelopeOutDto>) {
        call("POST", "/v1/e2e/messages", buildJsonObject {
            put("to", to); put("fromDevice", fromDevice); put("clientId", clientId)
            put("envelopes", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(EnvelopeOutDto.serializer()), envelopes))
        }, authed = true)
    }
    suspend fun inbox(deviceId: String): List<EnvelopeDto> = json.decodeFromString<InboxResponse>(call("GET", "/v1/e2e/inbox?deviceId=$deviceId", null, authed = true)).envelopes
    suspend fun ackInbox(deviceId: String, ids: List<String>) {
        call("POST", "/v1/e2e/inbox/ack", buildJsonObject { put("deviceId", deviceId); put("ids", kotlinx.serialization.json.JsonArray(ids.map { kotlinx.serialization.json.JsonPrimitive(it) })) }, authed = true)
    }

    /** This install, for push and the user's home time zone. [pushToken] is null until Firebase is configured. */
    suspend fun registerDevice(id: String, pushToken: String?) {
        call("PUT", "/v1/devices", buildJsonObject {
            put("id", id); put("platform", "android"); put("pushToken", pushToken); put("appVersion", BuildConfig.VERSION_NAME)
            put("locale", java.util.Locale.getDefault().toLanguageTag()); put("timezone", tz)
        }, authed = true)
    }

    /** Current access token for the realtime socket's first frame (never put in the URL). */
    fun accessToken(): String? = vault.read()?.access
    /** Opens the realtime socket; the caller sends the auth frame. */
    fun openSocket(listener: okhttp3.WebSocketListener): okhttp3.WebSocket =
        socketHttp.newWebSocket(Request.Builder().url(BuildConfig.API_BASE.replaceFirst("http", "ws") + "/v1/rt").header("X-Platform", "android").header("X-App-Version", BuildConfig.VERSION_NAME).build(), listener)

    suspend fun push(mutations: List<MutationDto>): PushResponse =
        json.decodeFromString(call("POST", "/v1/sync/push", buildJsonObject { put("mutations", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(MutationDto.serializer()), mutations)) }, authed = true))
    suspend fun pull(since: Long): PullResponse = json.decodeFromString(call("GET", "/v1/sync?since=$since", null, authed = true))

    private suspend inline fun <reified T> post(path: String, body: JsonObject): T = json.decodeFromString(call("POST", path, body, authed = false))

    private suspend fun call(method: String, path: String, body: JsonObject?, authed: Boolean, raw: okhttp3.RequestBody? = null, extraHeaders: Map<String, String> = emptyMap(), slow: Boolean = false): String = withContext(Dispatchers.IO) {
        val payload = raw ?: body?.toString()?.toRequestBody(JSON)
        val used = if (authed) vault.read() ?: throw UnauthorizedException() else null
        val first = execute(method, path, payload, used?.access, extraHeaders, slow)
        if (first.status != 401 || !authed) return@withContext first.orThrow()
        // Access token expired: refresh once (single flight), then retry.
        val fresh = refreshLock.withLock {
            val current = vault.read() ?: throw UnauthorizedException()
            if (current != used) return@withLock current                 // another call already refreshed
            val r = execute("POST", "/v1/auth/refresh", buildJsonObject { put("refreshToken", current.refresh) }.toString().toRequestBody(JSON), null)
            if (r.status == 401) throw UnauthorizedException()
            json.decodeFromString<RefreshResponse>(r.orThrow()).let { Tokens(it.accessToken, it.refreshToken) }.also(vault::write)
        }
        val second = execute(method, path, payload, fresh.access, extraHeaders, slow)
        if (second.status == 401) throw UnauthorizedException()
        second.orThrow()
    }

    internal class Raw(val status: Int, val text: String)

    internal fun Raw.orThrow(): String {
        if (status in 200..299) return text
        if (status == 426) _upgradeRequired.tryEmit(Unit)
        val obj = runCatching { json.decodeFromString<JsonObject>(text) }.getOrDefault(JsonObject(emptyMap()))
        throw ApiException(status, runCatching { obj["error"]!!.jsonPrimitive.content }.getOrDefault("http_$status"), obj)
    }

    private fun execute(method: String, path: String, body: okhttp3.RequestBody?, bearer: String?, extraHeaders: Map<String, String> = emptyMap(), slow: Boolean = false): Raw {
        val req = Request.Builder().url(BuildConfig.API_BASE + path)
            .method(method, body ?: if (method == "PUT" || method == "POST") ByteArray(0).toRequestBody(null) else null)
            .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }
            .header("X-Platform", "android").header("X-App-Version", BuildConfig.VERSION_NAME)
            .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
            .build()
        try {
            (if (slow) slowHttp else http).newCall(req).execute().use { network.reported(true); return Raw(it.code, it.body?.string().orEmpty()) }
        } catch (e: IOException) {
            // Couldn't connect at all (not a slow reply): the server is unreachable from this network.
            if (e is java.net.ConnectException || e is java.net.UnknownHostException || e is java.net.NoRouteToHostException ||
                (e is java.net.SocketTimeoutException && e.message.orEmpty().contains("connect", ignoreCase = true))) network.reported(false)
            throw OfflineException(e)
        }
    }

    private companion object { val JSON = "application/json; charset=utf-8".toMediaType(); val JPEG = "image/jpeg".toMediaType() }
}
