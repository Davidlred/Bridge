package app.thebridge.data

import kotlinx.coroutines.tasks.await
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.thebridge.data.local.AllianceMessageEntity
import app.thebridge.data.local.BridgeDb
import app.thebridge.data.local.SocialCacheEntity
import app.thebridge.data.remote.AllianceDto
import app.thebridge.data.remote.AllianceMessageDto
import app.thebridge.data.remote.ApiClient
import app.thebridge.data.remote.ApiException
import app.thebridge.data.remote.CommunityDto
import app.thebridge.data.remote.InvitePreviewDto
import app.thebridge.data.remote.OfflineException
import app.thebridge.data.remote.PostDto
import app.thebridge.data.session.SessionManager
import app.thebridge.data.sync.SyncWorker
import app.thebridge.util.UuidV7
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** Profile → Privacy → Community sharing. Streaks only by default. */
enum class ShareLevel(val wire: String) {
    Nothing("nothing"), Streaks("streaks"), All("all");
    fun next() = entries[(ordinal + 1) % entries.size]
    companion object { fun of(s: String?) = entries.firstOrNull { it.wire == s } ?: Streaks }
}

enum class Reaction(val wire: String) { Respect("respect"), KeepGoing("keepGoing"), Inspired("inspired") }

/**
 * Community, alliances and alliance chat. Social data lives on the server; the last copy is cached in the
 * local database so screens open instantly and read offline. Chat written offline is queued and sent later.
 */
@Singleton
class SocialRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val db: BridgeDb,
    private val api: ApiClient,
    private val session: SessionManager,
    private val goals: GoalRepository,
    private val store: DataStore<Preferences>,
) {
    val json get() = api.json
    private val dao get() = db.social()
    private val allianceList = ListSerializer(AllianceDto.serializer())

    val share: Flow<ShareLevel> = store.data.map { ShareLevel.of(it[SHARE]) }

    suspend fun setShare(level: ShareLevel) {
        store.edit { it[SHARE] = level.wire }
        session.user.value?.id?.let { goals.saveSetting(it, "share", level.wire) }
    }

    // --- Community feed ---
    fun community(goalId: String): Flow<CommunityDto?> = dao.cached("feed:$goalId").map { e -> e?.let { runCatching { json.decodeFromString(CommunityDto.serializer(), it.json) }.getOrNull() } }

    suspend fun refreshCommunity(goalId: String) = saveCommunity(goalId, api.community(goalId))

    private suspend fun saveCommunity(goalId: String, c: CommunityDto) = dao.cache(SocialCacheEntity("feed:$goalId", json.encodeToString(CommunityDto.serializer(), c)))

    private suspend fun editCommunity(goalId: String, f: (CommunityDto) -> CommunityDto) {
        val now = community(goalId).first() ?: return
        saveCommunity(goalId, f(now))
    }

    /** Optimistic: the count changes at once and is put back if the server can't be reached. */
    suspend fun react(goalId: String, postId: String, r: Reaction, on: Boolean) {
        val flip = { c: CommunityDto, add: Boolean ->
            c.copy(posts = c.posts.map { p ->
                if (p.id != postId) p else {
                    val d = if (add) 1 else -1
                    val n = p.reactions
                    p.copy(
                        reactions = when (r) { Reaction.Respect -> n.copy(respect = (n.respect + d).coerceAtLeast(0)); Reaction.KeepGoing -> n.copy(keepGoing = (n.keepGoing + d).coerceAtLeast(0)); Reaction.Inspired -> n.copy(inspired = (n.inspired + d).coerceAtLeast(0)) },
                        reacted = if (add) p.reacted + r.wire else p.reacted - r.wire,
                    )
                }
            })
        }
        editCommunity(goalId) { flip(it, on) }
        try { api.react(postId, r.wire, on) } catch (e: Exception) { editCommunity(goalId) { flip(it, !on) }; throw e }
    }

    /** Reported posts disappear for the reporter straight away. */
    suspend fun reportPost(goalId: String, postId: String, reason: String) {
        api.report("post", postId, reason)
        editCommunity(goalId) { c -> c.copy(posts = c.posts.filterNot { it.id == postId }) }
    }

    suspend fun block(userId: String, goalId: String? = null) {
        api.block(userId)
        if (goalId != null) editCommunity(goalId) { c -> c.copy(posts = c.posts.filterNot { it.userId == userId }) }
    }

    /**
     * After a task is ticked: share a full day, a streak milestone or a step across the bridge. The server
     * checks the claim against its completions and the sharing setting, and posts each one only once.
     */
    suspend fun announce(goalId: String) {
        if (share.first() == ShareLevel.Nothing) return
        val g = goals.activeGoals().first().find { it.goal.id == goalId } ?: return
        val p = g.progress
        val claims = buildList {
            if (p.streak in STREAK_MARKS || (p.streak > 100 && p.streak % 50 == 0)) add("streak" to p.streak)
            if (p.allDone) add("day_complete" to null)
            MILESTONES.lastOrNull { p.fraction * 100 >= it }?.let { add("milestone" to it) }
        }
        for ((kind, value) in claims) runCatching { api.announce(goalId, kind, value) }       // best effort; nothing to retry offline
    }

    // --- Alliances ---
    fun alliances(): Flow<List<AllianceDto>> = dao.cached("alliances").map { e -> e?.let { runCatching { json.decodeFromString(allianceList, it.json) }.getOrNull() }.orEmpty() }

    suspend fun refreshAlliances(): List<AllianceDto> = api.alliances().also { saveAlliances(it) }

    private suspend fun saveAlliances(list: List<AllianceDto>) = dao.cache(SocialCacheEntity("alliances", json.encodeToString(allianceList, list)))

    private suspend fun upsertAlliance(a: AllianceDto) = saveAlliances(alliances().first().filterNot { it.id == a.id } + a)

    suspend fun create(name: String, planMode: String, goalId: String): AllianceDto = api.createAlliance(name.trim(), planMode, goalId).also { upsertAlliance(it) }

    suspend fun preview(code: String): InvitePreviewDto = api.previewAlliance(code.trim().uppercase())

    /** Joining a shared plan adds its goal and tasks; they arrive with the next sync. */
    suspend fun join(code: String, goalId: String?): AllianceDto =
        api.joinAlliance(code.trim().uppercase(), goalId).also { upsertAlliance(it); SyncWorker.kick(ctx) }

    suspend fun leave(id: String) {
        api.leaveAlliance(id)
        saveAlliances(alliances().first().filterNot { it.id == id })
        SyncWorker.kick(ctx)                                     // a shared goal becomes a personal one
    }

    suspend fun applyPlanNow(id: String) = api.applyPlanNow(id)

    // --- Alliance chat ---
    fun messages(allianceId: String): Flow<List<AllianceMessageEntity>> = dao.messages(allianceId)

    suspend fun refreshMessages(allianceId: String) = dao.replace(allianceId, api.allianceMessages(allianceId).map { it.toEntity(allianceId) })

    /** Shows the message at once; it sends now, or stays "queued" until the connection is back. */
    suspend fun send(allianceId: String, body: String): SendResult {
        val row = AllianceMessageEntity(UuidV7.next(), allianceId, name = null, body = body.trim(), mine = true, status = "queued")
        dao.upsert(row)
        return deliver(row)
    }

    suspend fun retry(m: AllianceMessageEntity): SendResult = deliver(m.copy(status = "queued").also { dao.upsert(it) })

    suspend fun discard(m: AllianceMessageEntity) = dao.remove(m.id)

    /** Called when the network comes back. */
    suspend fun flushQueued() { dao.queued().forEach { deliver(it) } }

    private suspend fun deliver(m: AllianceMessageEntity): SendResult = try {
        val sent = api.sendAllianceMessage(m.allianceId, m.id, m.body)
        dao.upsert(m.copy(serverId = sent.id, userId = sent.userId, name = sent.name, createdAt = sent.createdAt, status = "sent"))
        SendResult.Sent
    } catch (e: OfflineException) {
        SendResult.Queued
    } catch (e: ApiException) {
        dao.upsert(m.copy(status = "failed"))
        if (e.code == "message_blocked") SendResult.Blocked else SendResult.Failed
    } catch (e: Exception) {
        dao.upsert(m.copy(status = "failed")); SendResult.Failed
    }

    suspend fun reportMessage(m: AllianceMessageEntity, reason: String) {
        api.report("message", m.serverId ?: return, reason)
        dao.remove(m.id)
    }

    // --- Game Night, rankings, Hall of Fame ---
    suspend fun gameStatus(allianceId: String) = api.gameStatus(allianceId)
    suspend fun openGame(allianceId: String) = api.openGame(allianceId)
    suspend fun nudge(allianceId: String) = api.nudge(allianceId)
    suspend fun shareGame(allianceId: String, gameId: String) = api.shareGame(allianceId, gameId)
    suspend fun rating(allianceId: String) = api.rating(allianceId)
    suspend fun rankings(allianceId: String) = api.rankings(allianceId)
    suspend fun badges() = api.badges()
    suspend fun setBadgesPublic(on: Boolean) { session.user.value?.id?.let { goals.saveSetting(it, "badgesPublic", on) } }

    /** Push registration for this install (no token until Firebase is configured), plus the home time zone. */
    suspend fun registerDevice() { runCatching { api.registerDevice(session.deviceId(), pushToken()) } }

    /** FCM token for this install, or null when Firebase isn't configured (no google-services.json) or Play services are missing. */
    private suspend fun pushToken(): String? = runCatching {
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token.await()
    }.getOrNull()

    enum class SendResult { Sent, Queued, Failed, Blocked }

    private companion object {
        val SHARE = stringPreferencesKey("community_share")
        val STREAK_MARKS = setOf(7, 14, 21, 30, 50, 75, 100)
        val MILESTONES = listOf(25, 50, 75, 100)
    }
}

internal fun AllianceMessageDto.toEntity(allianceId: String) =
    AllianceMessageEntity(clientId ?: id, allianceId, serverId = id, userId = userId, name = name, body = body, mine = mine, createdAt = createdAt, status = "sent")

/**
 * One WebSocket while any social screen is open (reference counted). The first frame authenticates;
 * new alliance messages go straight into the local database, which the chat screen reads.
 */
@Singleton
class RealtimeClient @Inject constructor(private val api: ApiClient, private val db: BridgeDb) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: WebSocket? = null
    private var users = 0
    private var attempt = 0
    private var reconnect: Job? = null
    private val _events = kotlinx.coroutines.flow.MutableSharedFlow<JsonObject>(extraBufferCapacity = 64)
    /** Game frames (`game:state`, `game:role`, `game:error`) for the Game Night screen. */
    val events: kotlinx.coroutines.flow.SharedFlow<JsonObject> = _events
    private val _connected = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** True once the server has accepted the auth frame; a reconnect flips it back, so screens can re-join. */
    val connected: kotlinx.coroutines.flow.StateFlow<Boolean> = _connected
    private val _dmPings = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    /** "Something is waiting in your encrypted inbox" (the event carries no content). */
    val dmPings: kotlinx.coroutines.flow.SharedFlow<Unit> = _dmPings

    fun send(frame: JsonObject): Boolean = socket?.send(frame.toString()) == true

    @Synchronized fun acquire() { if (++users == 1) connect() }

    @Synchronized fun release() {
        if (--users > 0) return
        users = 0; reconnect?.cancel(); socket?.close(1000, null); socket = null; _connected.value = false
    }

    /** After joining or creating an alliance, so its messages arrive too. */
    fun resubscribe() { socket?.send("""{"type":"subscribe"}""") }

    @Synchronized private fun connect() {
        val token = api.accessToken() ?: return
        socket = api.openSocket(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempt = 0
                webSocket.send(buildJsonObject { put("type", "auth"); put("token", token) }.toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) { scope.launch { handle(text) } }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { retry(refresh = code == 4401) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { retry(refresh = false) }
        })
    }

    @Synchronized private fun retry(refresh: Boolean) {
        socket = null; _connected.value = false
        if (users == 0) return
        reconnect?.cancel()
        reconnect = scope.launch {
            if (refresh) runCatching { api.me() }                      // refreshes an expired access token
            delay(minOf(30_000L, 1_000L shl minOf(attempt++, 5)))
            synchronized(this@RealtimeClient) { if (users > 0 && socket == null) connect() }
        }
    }

    private suspend fun handle(text: String) {
        val e = runCatching { api.json.decodeFromString<JsonObject>(text) }.getOrNull() ?: return
        when (e["type"]?.jsonPrimitive?.content) {
            "message" -> {
                val allianceId = e["allianceId"]?.jsonPrimitive?.content ?: return
                val m = runCatching { api.json.decodeFromJsonElement(AllianceMessageDto.serializer(), e["message"]!!) }.getOrNull() ?: return
                db.social().upsert(m.toEntity(allianceId))
            }
            "hidden" -> e["id"]?.jsonPrimitive?.content?.let { db.social().removeByServerId(it) }   // removed by moderation
            "ready" -> _connected.value = true
            "dm" -> _dmPings.emit(Unit)
            else -> if (e["type"]?.jsonPrimitive?.content?.startsWith("game:") == true) _events.emit(e)
        }
    }
}
