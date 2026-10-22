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
import app.thebridge.data.remote.LinkDto
import app.thebridge.data.remote.PlanStepDto
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

/** A task on today's plan: a daily habit, or a step (one-time, [carriedFrom] its day when that has passed). */
data class PlanTask(val task: TaskEntity, val done: Boolean, val links: List<LinkDto> = emptyList(), val carriedFrom: LocalDate? = null) {
    val isAi: Boolean get() = task.origin == "ai"
    val readOnly: Boolean get() = task.origin == "shared"
    val isStep: Boolean get() = task.onDate != null
}

data class GoalState(
    val goal: GoalEntity,
    val tasks: List<PlanTask>,
    val progress: GoalProgress,
    val feasibility: Feasibility?,
    val images: Map<String, GoalImageEntity>,
    /** Today's reflection for this goal has text or a mood ("Locked in."). */
    val journaledToday: Boolean = false,
    /** Daily habit titles, and step titles done and still open: what the next week of steps is planned from. */
    val habits: List<String> = emptyList(),
    val doneSteps: List<String> = emptyList(),
    val openSteps: List<String> = emptyList(),
) {
    /** No step left to do: offer to plan the next ones. */
    val needsSteps: Boolean get() = openSteps.isEmpty()
    val future: GoalImageEntity? get() = images["future"]
    val reality: GoalImageEntity? get() = images["reality"]
}

data class NewTask(val title: String, val priority: String = "Medium", val timeOfDay: String? = null, val minutes: Int? = null, val rationale: String? = null, val notes: String? = null, val ai: Boolean = false, val resources: List<LinkDto> = emptyList())
/** A one-time step on plan day [day] (1 = the goal's first day). */
data class NewStep(val day: Int, val title: String, val minutes: Int? = null, val rationale: String? = null, val resources: List<LinkDto> = emptyList())
data class NewGoal(val id: String, val label: String, val statement: String, val deadline: LocalDate, val routine: String?, val feasibility: Feasibility?, val tasks: List<NewTask>, val category: String? = null, val steps: List<NewStep> = emptyList())

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
            val (steps, habits) = live.partition { it.onDate != null }
            val created = habits.associate { it.id to createdDay(it.id, LocalDate.parse(g.startDate)) }
            val stepDays = steps.associate { it.id to LocalDate.parse(it.onDate) }
            GoalState(
                goal = g,
                // Habits every day; a step on its day and after until it is done. Steps first: they are today's specific action.
                tasks = live.filter { Progress.showsToday(stepDays[it.id], doneOn[it.id].orEmpty(), today) }
                    .map { PlanTask(it, today in doneOn[it.id].orEmpty(), links(it.resources), stepDays[it.id]?.takeIf { d -> d.isBefore(today) }) }
                    .sortedWith(compareBy({ if (it.isStep) 0 else 1 }, { it.task.onDate }, { PRIORITY_ORDER[it.task.priority] ?: 1 }, { it.task.position })),
                progress = Progress.of(LocalDate.parse(g.startDate), LocalDate.parse(g.deadline), today, created, doneOn.filterKeys { it in created || it in stepDays }, stepDays),
                habits = habits.map { it.title },
                doneSteps = steps.filter { doneOn[it.id].orEmpty().isNotEmpty() }.sortedBy { it.onDate }.map { it.title },
                openSteps = steps.filter { doneOn[it.id].orEmpty().isEmpty() }.sortedBy { it.onDate }.map { it.title },
                feasibility = g.feasibility?.let { runCatching { json.decodeFromString(Feasibility.serializer(), it) }.getOrNull() },
                images = images.filter { it.goalId == g.id }.associateBy { it.kind },
                journaledToday = g.id in journaled,
            )
        }
    }

    private val linkList = kotlinx.serialization.builtins.ListSerializer(LinkDto.serializer())
    private fun links(raw: String): List<LinkDto> = runCatching { json.decodeFromString(linkList, raw) }.getOrDefault(emptyList())
    private fun linksJson(l: List<LinkDto>): String = json.encodeToString(linkList, l.take(3))

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
                val row = TaskEntity(UuidV7.next(), g.id, t.title.trim(), t.priority, t.timeOfDay, t.minutes, t.rationale, t.notes, if (t.ai) "ai" else "user", position = i, resources = linksJson(t.resources))
                db.tasks().upsert(row)
                enqueue("task", row.id, "upsert", taskPatch(row, create = true))
            }
            g.steps.filter { it.title.isNotBlank() }.forEachIndexed { i, s ->
                val row = stepRow(g.id, s.title, s.minutes, s.rationale, s.resources, start.plusDays((s.day - 1).coerceAtLeast(0).toLong()), 100 + i)
                db.tasks().upsert(row)
                enqueue("task", row.id, "upsert", taskPatch(row, create = true))
            }
        }
    }

    private fun stepRow(goalId: String, title: String, minutes: Int?, rationale: String?, resources: List<LinkDto>, day: LocalDate, position: Int) =
        TaskEntity(UuidV7.next(), goalId, title.trim().take(120), "Medium", minutes = minutes, rationale = rationale?.take(200), origin = "ai", position = position,
            onDate = day.toString(), resources = linksJson(resources))

    /** The next week of steps, planned by the AI; day 1 is today. */
    suspend fun addSteps(goalId: String, steps: List<PlanStepDto>) {
        val today = today()
        val first = db.tasks().nextPosition(goalId)
        write { enqueue ->
            steps.filter { it.title.isNotBlank() }.forEachIndexed { i, s ->
                val row = stepRow(goalId, s.title, s.minutes, s.rationale, s.resources, today.plusDays((s.day - 1).coerceAtLeast(0).toLong()), first + i)
                db.tasks().upsert(row)
                enqueue("task", row.id, "upsert", taskPatch(row, create = true))
            }
        }
    }

    private fun taskPatch(t: TaskEntity, create: Boolean) = buildJsonObject {
        if (create) {
            put("goalId", t.goalId); put("origin", t.origin); put("rationale", t.rationale); put("minutes", t.minutes)
            if (t.onDate != null) put("onDate", t.onDate)
            if (t.resources != "[]") put("resources", json.parseToJsonElement(t.resources))
        }
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
    suspend fun archiveGoal(id: String) = setStatus(id, "archived")

    /** Goals paused because Plus ended (read-only, history kept). */
    fun pausedGoals(): Flow<List<GoalEntity>> = db.goals().paused()

    /** The user picks which goals stay active. Pauses are queued before resumes so the server's limit check passes. */
    suspend fun chooseActive(keep: Set<String>) {
        val all = db.goals().activeOrPaused()
        val now = System.currentTimeMillis()
        write { enqueue ->
            for (g in all.filter { it.status == "active" && it.id !in keep }) {
                db.goals().upsert(g.copy(status = "paused", updatedAt = now)); enqueue("goal", g.id, "upsert", buildJsonObject { put("status", "paused") })
            }
            for (g in all.filter { it.status == "paused" && it.id in keep }) {
                db.goals().upsert(g.copy(status = "active", updatedAt = now)); enqueue("goal", g.id, "upsert", buildJsonObject { put("status", "active") })
            }
        }
    }

    /** The user says they made it (early, on the day or after the deadline). Earns the "goal" badge on the server. */
    suspend fun completeGoal(id: String) = setStatus(id, "completed")

    private suspend fun setStatus(id: String, status: String) {
        val g = db.goals().get(id) ?: return
        write { enqueue ->
            db.goals().upsert(g.copy(status = status, updatedAt = System.currentTimeMillis()))
            enqueue("goal", id, "upsert", buildJsonObject { put("status", status) })
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
