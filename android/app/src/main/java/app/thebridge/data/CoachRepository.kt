package app.thebridge.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.thebridge.data.local.BridgeDb
import app.thebridge.data.local.ChatMessageEntity
import app.thebridge.data.local.LocalDay
import app.thebridge.data.remote.ApiClient
import app.thebridge.data.remote.ApiException
import app.thebridge.data.remote.CoachEvent
import app.thebridge.data.remote.OfflineException
import app.thebridge.data.remote.StreamInterrupted
import app.thebridge.data.remote.SuggestionDto
import app.thebridge.util.UuidV7
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import javax.inject.Inject
import javax.inject.Singleton

/** Why a reply didn't arrive, for the UI copy. */
enum class CoachProblem { Offline, Quota, Interrupted, Failed }

/**
 * The coach conversation lives on this device. Each send streams the reply into a local message as tokens arrive,
 * so the UI just observes the table. The model sees the goals, today's tasks, the streak and the last 20 messages.
 */
@Singleton
class CoachRepository @Inject constructor(
    private val db: BridgeDb,
    private val api: ApiClient,
    private val goals: GoalRepository,
    private val store: DataStore<Preferences>,
    @dagger.hilt.android.qualifiers.ApplicationContext private val ctx: android.content.Context,
) {
    val messages: Flow<List<ChatMessageEntity>> = db.chat().all()
    /** The current conversation. On a fresh device it continues the newest conversation pulled from the account. */
    val conversation: Flow<String?> = kotlinx.coroutines.flow.combine(store.data.map { it[CONVERSATION] }, messages) { pref, msgs -> pref ?: msgs.lastOrNull()?.conversation }

    private suspend fun conversationId(): String = conversation.first() ?: UuidV7.next().also { id -> store.edit { it[CONVERSATION] = id } }

    /** Suggestion state and deletes sync to the account's other devices; messages themselves are stored by the server with the reply. */
    private suspend fun enqueue(id: String, op: String, patch: kotlinx.serialization.json.JsonObject) {
        db.chat().enqueue(app.thebridge.data.local.OutboxEntity(UuidV7.next(), "chat", id, op, patch.toString()))
        app.thebridge.data.sync.SyncWorker.kick(ctx)
    }

    /** Starts a fresh conversation; earlier ones stay on the device behind "Show earlier conversation". */
    suspend fun newConversation() { store.edit { it[CONVERSATION] = UuidV7.next() } }

    /**
     * Sends [text] (or re-sends the failed message [retryId]) and streams the reply.
     * [onFirstToken] lets the UI switch the mascot from thinking to talking. Returns null on success.
     */
    suspend fun send(text: String, focusGoalId: String?, mode: String = "chat", retryId: String? = null, onFirstToken: () -> Unit = {}): CoachProblem? {
        val conv = conversationId()
        val history = db.chat().recent(conv, 21).reversed().filter { it.id != retryId && it.status != "failed" && it.text.isNotBlank() }.takeLast(20)
        val userMsg = retryId?.let { db.chat().get(it) }?.copy(status = "sending") ?: ChatMessageEntity(UuidV7.next(), conv, "user", text.trim(), status = "sending")
        db.chat().upsert(userMsg)
        val active = goals.activeGoals().first()
        val reply = ChatMessageEntity(UuidV7.next(), conv, "coach", "", status = "streaming", createdAt = System.currentTimeMillis() + 1)
        val body = buildJsonObject {
            put("message", userMsg.text); put("mode", mode); put("localDate", LocalDay.today()); put("focusGoalId", focusGoalId)
            // Ids let the server store this exchange so it follows the account to other devices.
            put("conversation", conv); put("messageId", userMsg.id); put("replyId", reply.id); put("sentAt", userMsg.createdAt)
            putJsonArray("history") { history.forEach { m -> add(buildJsonObject { put("role", m.role); put("text", m.text.take(4000)) }) } }
            putJsonArray("goals") {
                active.take(3).forEach { g ->
                    add(buildJsonObject {
                        put("id", g.goal.id); put("label", g.goal.label); put("statement", g.goal.statement); put("deadline", g.goal.deadline)
                        put("day", g.progress.day); put("total", g.progress.total); put("streak", g.progress.streak)
                        put("tasks", JsonArray(g.tasks.take(20).map { t -> buildJsonObject { put("title", t.task.title); put("done", t.done); put("priority", t.task.priority); put("timeOfDay", t.task.timeOfDay) } }))
                    })
                }
            }
        }
        var started = false
        val text = StringBuilder()
        return try {
            api.coach(body) { e ->
                when (e) {
                    is CoachEvent.Token -> {
                        if (!started) { started = true; db.chat().upsert(userMsg.copy(status = "sent")); db.chat().upsert(reply); onFirstToken() }
                        text.append(e.text)
                        db.chat().update(reply.id, text.toString(), "streaming")
                    }
                    is CoachEvent.Suggest -> db.chat().upsert(reply.copy(text = text.toString(), status = "streaming", suggestion = api.json.encodeToString(SuggestionDto.serializer(), e.suggestion), suggestionState = "pending"))
                }
            }
            // Keep the suggestion fields written above; only the text and status change here.
            db.chat().update(reply.id, text.toString(), "done")
            null
        } catch (e: StreamInterrupted) {
            // Keep the partial reply and say it was interrupted; the user can retry.
            if (started) db.chat().update(reply.id, text.toString(), "interrupted") else db.chat().upsert(userMsg.copy(status = "failed"))
            CoachProblem.Interrupted
        } catch (e: OfflineException) {
            db.chat().upsert(userMsg.copy(status = "failed")); CoachProblem.Offline
        } catch (e: ApiException) {
            db.chat().upsert(userMsg.copy(status = "failed")); if (e.code == "ai_quota") CoachProblem.Quota else CoachProblem.Failed
        } catch (e: Exception) {
            db.chat().upsert(userMsg.copy(status = "failed")); CoachProblem.Failed
        }
    }

    fun suggestion(m: ChatMessageEntity): SuggestionDto? = m.suggestion?.let { runCatching { api.json.decodeFromString(SuggestionDto.serializer(), it) }.getOrNull() }

    /** "Add to plan": the suggestion becomes an AI task on the goal in focus. */
    suspend fun addSuggestion(m: ChatMessageEntity, goalId: String): Boolean {
        val s = suggestion(m) ?: return false
        goals.addSuggestedTask(goalId, s.title, s.priority, s.timeOfDay, s.rationale)
        db.chat().upsert(m.copy(suggestionState = "added"))
        enqueue(m.id, "upsert", buildJsonObject { put("suggestionState", "added") })
        return true
    }

    suspend fun dismissSuggestion(m: ChatMessageEntity) {
        db.chat().upsert(m.copy(suggestionState = "dismissed"))
        enqueue(m.id, "upsert", buildJsonObject { put("suggestionState", "dismissed") })
    }

    /** Retry after "Reply interrupted": drop the partial reply and send the question again. */
    suspend fun retryInterrupted(reply: ChatMessageEntity, focusGoalId: String?, onFirstToken: () -> Unit): CoachProblem? {
        val question = db.chat().recent(reply.conversation, 50).firstOrNull { it.role == "user" && it.createdAt < reply.createdAt } ?: return CoachProblem.Failed
        db.chat().delete(reply.id)
        enqueue(reply.id, "delete", buildJsonObject {})
        return send(question.text, focusGoalId, retryId = question.id, onFirstToken = onFirstToken)
    }

    private companion object { val CONVERSATION = stringPreferencesKey("coach_conversation") }
}
