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

@Serializable data class UserDto(val id: String, val email: String? = null, val displayName: String, val coachName: String = "Arc", val createdAt: Long = 0, val photoUrl: String? = null)
/** [isNew]: the account was just created, so the user goes to goal onboarding. */
@Serializable data class AuthResponse(val user: UserDto, val accessToken: String, val refreshToken: String, val isNew: Boolean = false)
@Serializable private data class MeResponse(val user: UserDto)

@Serializable data class Feasibility(val verdict: String, val reason: String, val suggestedDeadline: String? = null)
@Serializable data class PlanTaskDto(val title: String, val priority: String, val timeOfDay: String, val minutes: Int, val rationale: String)
@Serializable data class PlanDto(val needsClarification: String? = null, val label: String, val goal: String, val feasibility: Feasibility, val tasks: List<PlanTaskDto>)
@Serializable private data class PlanResponse(val plan: PlanDto)
@Serializable private data class RealityResponse(val feasibility: Feasibility)
@Serializable data class GoalImageDto(val kind: String, val status: String, val url: String? = null, val withFace: Boolean = false)
@Serializable private data class ImagesResponse(val images: List<GoalImageDto>)
@Serializable private data class PhotoResponse(val photoUrl: String)
@Serializable private data class RefreshResponse(val accessToken: String, val refreshToken: String)

@Serializable data class MutationDto(val id: String, val entity: String, val entityId: String, val op: String, val patch: JsonElement, val clientTs: Long)
@Serializable data class PushResponse(val accepted: List<String> = emptyList(), val rejected: Map<String, String> = emptyMap())
@Serializable data class GoalDto(val id: String, val label: String, val statement: String, val startDate: String, val deadline: String, val status: String, val sourceAllianceId: String? = null, val colorIndex: Int = 0, val updatedAt: Long = 0, val routine: String? = null, val realityNote: String? = null, val feasibility: Feasibility? = null)
@Serializable data class TaskDto(val id: String, val goalId: String, val title: String, val priority: String, val timeOfDay: String? = null, val minutes: Int? = null, val rationale: String? = null, val notes: String? = null, val origin: String = "user", val sharedTaskId: String? = null, val position: Int = 0, val deletedAt: Long? = null, val updatedAt: Long = 0)
@Serializable data class CompletionDto(val taskId: String, val localDate: String, val completedAt: Long = 0)
@Serializable data class CompletionKey(val taskId: String, val localDate: String)
@Serializable data class Removed(val goals: List<String> = emptyList(), val completions: List<CompletionKey> = emptyList())
@Serializable data class PullResponse(val cursor: Long, val hasMore: Boolean = false, val goals: List<GoalDto> = emptyList(), val tasks: List<TaskDto> = emptyList(), val completions: List<CompletionDto> = emptyList(), val removed: Removed = Removed(), val settings: JsonObject? = null)

/** All HTTP goes through here. Repositories call it; ViewModels never do. */
@Singleton
class ApiClient @Inject constructor(private val http: OkHttpClient, private val vault: TokenVault) {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val refreshLock = Mutex()
    private val _upgradeRequired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
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

    suspend fun me(): UserDto = json.decodeFromString<MeResponse>(call("GET", "/v1/me", null, authed = true)).user

    // --- AI (all model calls happen on the server) ---
    suspend fun plan(goalText: String, deadline: String, today: String, routine: String, existingGoals: Int): PlanDto =
        json.decodeFromString<PlanResponse>(call("POST", "/v1/ai/plan", buildJsonObject {
            put("goalText", goalText); put("deadline", deadline); put("today", today); put("timezone", tz); put("routine", routine)
            put("locale", java.util.Locale.getDefault().toLanguageTag()); put("existingGoals", existingGoals)
        }, authed = true)).plan
    suspend fun reality(goalText: String, deadline: String, today: String, routine: String, tasks: List<String>): Feasibility =
        json.decodeFromString<RealityResponse>(call("POST", "/v1/ai/reality", buildJsonObject {
            put("goalText", goalText); put("deadline", deadline); put("today", today); put("routine", routine)
            put("tasks", kotlinx.serialization.json.JsonArray(tasks.take(20).map { kotlinx.serialization.json.JsonPrimitive(it.take(120)) }))
        }, authed = true)).feasibility
    suspend fun startImages(goalId: String, goalText: String, routine: String, kinds: List<String>): List<GoalImageDto> =
        json.decodeFromString<ImagesResponse>(call("POST", "/v1/ai/images", buildJsonObject {
            put("goalId", goalId); put("goalText", goalText); put("routine", routine); put("kinds", kotlinx.serialization.json.JsonArray(kinds.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }, authed = true)).images
    suspend fun images(goalId: String): List<GoalImageDto> = json.decodeFromString<ImagesResponse>(call("GET", "/v1/ai/images/$goalId", null, authed = true)).images

    /** The caller has shown the consent text; the header records that the user agreed. */
    suspend fun uploadPhoto(jpeg: ByteArray): String =
        json.decodeFromString<PhotoResponse>(call("PUT", "/v1/me/photo", null, authed = true, raw = jpeg.toRequestBody(JPEG), extraHeaders = mapOf("X-Photo-Consent" to "1"))).photoUrl
    suspend fun deletePhoto() { call("DELETE", "/v1/me/photo", null, authed = true) }

    suspend fun push(mutations: List<MutationDto>): PushResponse =
        json.decodeFromString(call("POST", "/v1/sync/push", buildJsonObject { put("mutations", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(MutationDto.serializer()), mutations)) }, authed = true))
    suspend fun pull(since: Long): PullResponse = json.decodeFromString(call("GET", "/v1/sync?since=$since", null, authed = true))

    private suspend inline fun <reified T> post(path: String, body: JsonObject): T = json.decodeFromString(call("POST", path, body, authed = false))

    private suspend fun call(method: String, path: String, body: JsonObject?, authed: Boolean, raw: okhttp3.RequestBody? = null, extraHeaders: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val payload = raw ?: body?.toString()?.toRequestBody(JSON)
        val used = if (authed) vault.read() ?: throw UnauthorizedException() else null
        val first = execute(method, path, payload, used?.access, extraHeaders)
        if (first.status != 401 || !authed) return@withContext first.orThrow()
        // Access token expired: refresh once (single flight), then retry.
        val fresh = refreshLock.withLock {
            val current = vault.read() ?: throw UnauthorizedException()
            if (current != used) return@withLock current                 // another call already refreshed
            val r = execute("POST", "/v1/auth/refresh", buildJsonObject { put("refreshToken", current.refresh) }.toString().toRequestBody(JSON), null)
            if (r.status == 401) throw UnauthorizedException()
            json.decodeFromString<RefreshResponse>(r.orThrow()).let { Tokens(it.accessToken, it.refreshToken) }.also(vault::write)
        }
        val second = execute(method, path, payload, fresh.access, extraHeaders)
        if (second.status == 401) throw UnauthorizedException()
        second.orThrow()
    }

    private class Raw(val status: Int, val text: String)

    private fun Raw.orThrow(): String {
        if (status in 200..299) return text
        if (status == 426) _upgradeRequired.tryEmit(Unit)
        val obj = runCatching { json.decodeFromString<JsonObject>(text) }.getOrDefault(JsonObject(emptyMap()))
        throw ApiException(status, runCatching { obj["error"]!!.jsonPrimitive.content }.getOrDefault("http_$status"), obj)
    }

    private fun execute(method: String, path: String, body: okhttp3.RequestBody?, bearer: String?, extraHeaders: Map<String, String> = emptyMap()): Raw {
        val req = Request.Builder().url(BuildConfig.API_BASE + path)
            .method(method, body ?: if (method == "PUT" || method == "POST") ByteArray(0).toRequestBody(null) else null)
            .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }
            .header("X-Platform", "android").header("X-App-Version", BuildConfig.VERSION_NAME)
            .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
            .build()
        try {
            http.newCall(req).execute().use { return Raw(it.code, it.body?.string().orEmpty()) }
        } catch (e: IOException) {
            throw OfflineException(e)
        }
    }

    private companion object { val JSON = "application/json; charset=utf-8".toMediaType(); val JPEG = "image/jpeg".toMediaType() }
}
