package app.thebridge.data

import android.content.Context
import androidx.room.withTransaction
import app.thebridge.data.local.BridgeDb
import app.thebridge.data.local.CompletionEntity
import app.thebridge.data.local.GoalEntity
import app.thebridge.data.local.GoalImageEntity
import app.thebridge.data.local.LocalDay
import app.thebridge.data.local.OutboxEntity
import app.thebridge.data.local.TaskEntity
import app.thebridge.data.remote.ApiClient
import app.thebridge.data.remote.Feasibility
import app.thebridge.data.sync.SyncWorker
import app.thebridge.domain.GoalProgress
import app.thebridge.domain.Progress
import app.thebridge.util.UuidV7
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A task on today's plan. */
data class PlanTask(val task: TaskEntity, val done: Boolean) {
    val isAi: Boolean get() = task.origin == "ai"
    val readOnly: Boolean get() = task.origin == "shared"
}

data class GoalState(
    val goal: GoalEntity,
    val tasks: List<PlanTask>,
    val progress: GoalProgress,
    val feasibility: Feasibility?,
    val images: Map<String, GoalImageEntity>,
    /** Today's reflection for this goal has text or a mood ("Locked in."). */
    val journaledToday: Boolean = false,
) {
    val future: GoalImageEntity? get() = images["future"]
    val reality: GoalImageEntity? get() = images["reality"]
}

data class NewTask(val title: String, val priority: String = "Medium", val timeOfDay: String? = null, val minutes: Int? = null, val rationale: String? = null, val notes: String? = null, val ai: Boolean = false)
data class NewGoal(val id: String, val label: String, val statement: String, val deadline: LocalDate, val routine: String?, val feasibility: Feasibility?, val tasks: List<NewTask>, val category: String? = null)

/**
 * Goals, tasks and completions. Every write puts the row and its outbox record in one transaction,
 * then nudges sync; the UI only ever reads the local database.
 */
@Singleton
class GoalRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val db: BridgeDb,
    private val api: ApiClient,
    private val reminders: app.thebridge.data.reminders.ReminderScheduler,
) {
    companion object {
        const val FREE_GOAL_LIMIT = 3
        val PRIORITY_ORDER = mapOf("High" to 0, "Medium" to 1, "Low" to 2)
    }

    private val json get() = api.json
    private fun today(): LocalDate = LocalDate.parse(LocalDay.today())

    /** Active goals with today's plan and progress, oldest first (the order of the goal card colours). */
    fun activeGoals(): Flow<List<GoalState>> = combine(db.goals().active(), db.goals().activeTasks(), db.goals().activeCompletions(), db.goals().images(), db.journal().all()) { goals, tasks, completions, images, journal ->
        val today = today()
        val journaled = journal.filter { it.localDate == today.toString() && (it.body.isNotBlank() || it.mood != null) }.mapNotNull { it.goalId }.toSet()
        val doneOn = completions.groupBy { it.taskId }.mapValues { (_, c) -> c.map { LocalDate.parse(it.localDate) }.toSet() }
        goals.map { g ->
            val live = tasks.filter { it.goalId == g.id && it.deletedAt == null }
            val created = live.associate { it.id to createdDay(it.id, LocalDate.parse(g.startDate)) }
            GoalState(
                goal = g,
                tasks = live.map { PlanTask(it, today in doneOn[it.id].orEmpty()) }
                    .sortedWith(compareBy({ PRIORITY_ORDER[it.task.priority] ?: 1 }, { it.task.position })),
                progress = Progress.of(LocalDate.parse(g.startDate), LocalDate.parse(g.deadline), today, created, doneOn.filterKeys { it in created }),
                feasibility = g.feasibility?.let { runCatching { json.decodeFromString(Feasibility.serializer(), it) }.getOrNull() },
                images = images.filter { it.goalId == g.id }.associateBy { it.kind },
                journaledToday = g.id in journaled,
            )
        }
    }

    /** UUIDv7 ids carry their creation time; tasks never count as missed before they existed. */
    private fun createdDay(id: String, start: LocalDate): LocalDate = runCatching {
        val ms = UUID.fromString(id).mostSignificantBits ushr 16
        val d = LocalDate.parse(LocalDay.today(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDateTime()))
        if (d.isBefore(start)) start else d
    }.getOrDefault(start)

    suspend fun activeCount(): Int = db.goals().activeCount()
    suspend fun goalOf(taskId: String): String? = db.tasks().get(taskId)?.goalId
    /** The routine from the most recent goal, offered again when adding another goal. */
    suspend fun lastRoutine(): String? = db.goals().latest()?.routine

    private suspend fun write(block: suspend (enqueue: suspend (entity: String, id: String, op: String, patch: JsonObject) -> Unit) -> Unit) {
        db.withTransaction {
            block { entity, id, op, patch -> db.goals().enqueue(OutboxEntity(UuidV7.next(), entity, id, op, patch.toString())) }
        }
        SyncWorker.kick(ctx)
        reminders.rescheduleAsync()                            // task times or goals may have changed
    }

    suspend fun createGoal(g: NewGoal) {
        val start = today()
        val colour = db.goals().activeCount() % 3
        val goal = GoalEntity(g.id, g.label.trim(), g.statement.trim(), start.toString(), g.deadline.toString(), colorIndex = colour, routine = g.routine,
            feasibility = g.feasibility?.let { json.encodeToString(Feasibility.serializer(), it) }, category = g.category)
        write { enqueue ->
            db.goals().upsert(goal)
            enqueue("goal", g.id, "upsert", buildJsonObject {
                put("label", goal.label); put("statement", goal.statement); put("startDate", goal.startDate); put("deadline", goal.deadline)
                put("colorIndex", colour); put("routine", g.routine); put("category", g.category)
                if (g.feasibility != null) put("feasibility", json.encodeToJsonElement(Feasibility.serializer(), g.feasibility))
            })
            g.tasks.filter { it.title.isNotBlank() }.forEachIndexed { i, t ->
                val row = TaskEntity(UuidV7.next(), g.id, t.title.trim(), t.priority, t.timeOfDay, t.minutes, t.rationale, t.notes, if (t.ai) "ai" else "user", position = i)
                db.tasks().upsert(row)
                enqueue("task", row.id, "upsert", taskPatch(row, create = true))
            }
        }
    }

    private fun taskPatch(t: TaskEntity, create: Boolean) = buildJsonObject {
        if (create) { put("goalId", t.goalId); put("origin", t.origin); put("rationale", t.rationale); put("minutes", t.minutes) }
        put("title", t.title); put("priority", t.priority); put("timeOfDay", t.timeOfDay); put("notes", t.notes); put("position", t.position)
    }

    /** Only the fields passed are changed and sent. The deadline is whatever the user picked. */
    suspend fun updateGoal(id: String, label: String? = null, deadline: LocalDate? = null, realityNote: String? = null, feasibility: Feasibility? = null, clearFeasibility: Boolean = false) {
        val g = db.goals().get(id) ?: return
        val next = g.copy(
            label = label?.trim() ?: g.label, deadline = deadline?.toString() ?: g.deadline, realityNote = realityNote ?: g.realityNote,
            feasibility = when { feasibility != null -> json.encodeToString(Feasibility.serializer(), feasibility); clearFeasibility -> null; else -> g.feasibility },
            updatedAt = System.currentTimeMillis(),
        )
        if (next == g.copy(updatedAt = next.updatedAt)) return
        write { enqueue ->
            db.goals().upsert(next)
            enqueue("goal", id, "upsert", buildJsonObject {
                if (label != null) put("label", next.label)
                if (deadline != null) put("deadline", next.deadline)
                if (realityNote != null) put("realityNote", next.realityNote)
                if (feasibility != null) put("feasibility", json.encodeToJsonElement(Feasibility.serializer(), feasibility))
                else if (clearFeasibility) put("feasibility", kotlinx.serialization.json.JsonNull)
            })
        }
    }

    /** Archived goals leave the daily plan; their images, journal and history are kept. */
    suspend fun archiveGoal(id: String) {
        val g = db.goals().get(id) ?: return
        write { enqueue ->
            db.goals().upsert(g.copy(status = "archived", updatedAt = System.currentTimeMillis()))
            enqueue("goal", id, "upsert", buildJsonObject { put("status", "archived") })
        }
    }

    suspend fun addTask(goalId: String, title: String, notes: String?, priority: String, timeOfDay: String?) {
        val row = TaskEntity(UuidV7.next(), goalId, title.trim(), priority, timeOfDay?.trim()?.ifEmpty { null }, notes = notes?.trim()?.ifEmpty { null }, position = db.tasks().nextPosition(goalId))
        write { enqueue -> db.tasks().upsert(row); enqueue("task", row.id, "upsert", taskPatch(row, create = true)) }
    }

    /** A coach suggestion accepted with "Add to plan": an AI task with its reason. */
    suspend fun addSuggestedTask(goalId: String, title: String, priority: String, timeOfDay: String?, rationale: String?) {
        val row = TaskEntity(UuidV7.next(), goalId, title.trim().take(120), priority, timeOfDay?.take(24), rationale = rationale?.take(200), origin = "ai", position = db.tasks().nextPosition(goalId))
        write { enqueue -> db.tasks().upsert(row); enqueue("task", row.id, "upsert", taskPatch(row, create = true)) }
    }

    /** Synced user settings (e.g. coachReadsJournal). [userId] is the settings row id on the server. */
    suspend fun saveSetting(userId: String, key: String, value: Boolean) {
        write { enqueue -> enqueue("settings", userId, "upsert", buildJsonObject { put(key, value) }) }
    }
    suspend fun saveSetting(userId: String, key: String, value: String) {
        write { enqueue -> enqueue("settings", userId, "upsert", buildJsonObject { put(key, value) }) }
    }

    suspend fun updateTask(id: String, title: String, notes: String?, priority: String, timeOfDay: String?) {
        val t = db.tasks().get(id) ?: return
        if (t.origin == "shared") return                       // shared-plan tasks are read-only for members
        val next = t.copy(title = title.trim(), notes = notes?.trim()?.ifEmpty { null }, priority = priority, timeOfDay = timeOfDay?.trim()?.ifEmpty { null }, updatedAt = System.currentTimeMillis())
        write { enqueue ->
            db.tasks().upsert(next)
            enqueue("task", id, "upsert", buildJsonObject {
                if (next.title != t.title) put("title", next.title)
                if (next.notes != t.notes) put("notes", next.notes)
                if (next.priority != t.priority) put("priority", next.priority)
                if (next.timeOfDay != t.timeOfDay) put("timeOfDay", next.timeOfDay)
            })
        }
    }

    suspend fun deleteTask(id: String) {
        val t = db.tasks().get(id) ?: return
        if (t.origin == "shared") return
        write { enqueue -> db.tasks().upsert(t.copy(deletedAt = System.currentTimeMillis())); enqueue("task", id, "delete", JsonObject(emptyMap())) }
    }

    /** Ticks a task for the current local day (which closes at 03:00). */
    suspend fun setDone(taskId: String, done: Boolean) {
        val day = LocalDay.today()
        write { enqueue ->
            if (done) db.tasks().complete(CompletionEntity(taskId, day)) else db.tasks().uncomplete(taskId, day)
            enqueue("completion", taskId, if (done) "complete" else "uncomplete", buildJsonObject { put("localDate", JsonPrimitive(day)) })
        }
    }
}
