package app.thebridge.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.datastore.preferences.core.edit
import androidx.work.*
import app.thebridge.data.local.BridgeDb
import app.thebridge.data.local.CompletionEntity
import app.thebridge.data.local.GoalEntity
import app.thebridge.data.local.JournalEntity
import app.thebridge.data.local.OutboxEntity
import app.thebridge.data.local.TaskEntity
import app.thebridge.data.remote.ApiClient
import app.thebridge.data.remote.MutationDto
import app.thebridge.data.remote.PullResponse
import app.thebridge.data.remote.UnauthorizedException
import app.thebridge.data.session.SessionManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Drains the outbox, then pulls server changes. Unique work: never two syncs at once. */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val db: BridgeDb,
    private val api: SyncApi,
    private val session: SessionManager,
    private val reminders: app.thebridge.data.reminders.ReminderScheduler,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = try {
        while (true) {
            val batch = db.outbox().batch()
            if (batch.isEmpty()) break
            val res = api.push(batch)                                   // idempotent by outbox id
            val done = res.accepted + res.rejected.keys                 // rejected (e.g. shared_task_readonly) are dropped; row re-pulled
            if (done.isEmpty()) break
            db.outbox().remove(done.toList())
        }
        // Pulling over unsent local edits would overwrite them, so only pull once the outbox is empty.
        if (db.outbox().countNow() == 0) { api.pullAndApply(db); reminders.reschedule() }
        Result.success()
    } catch (e: UnauthorizedException) {
        session.expired()                                               // UI shows re-auth sheet; outbox kept
        Result.failure()
    } catch (e: Exception) {
        if (runAttemptCount < 8) Result.retry() else Result.failure()
    }

    companion object {
        private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Call after every local mutation (debounced by the 1.5s initial delay). */
        fun kick(ctx: Context) = WorkManager.getInstance(ctx).enqueueUniqueWork(
            "sync-now", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(net).setInitialDelay(1500, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build(),
        )

        fun schedulePeriodic(ctx: Context) = WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "sync-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(net).build(),
        )

        fun cancel(ctx: Context) { WorkManager.getInstance(ctx).cancelUniqueWork("sync-now") }
    }
}

interface SyncApi {
    data class PushResult(val accepted: Set<String>, val rejected: Map<String, String>)
    suspend fun push(batch: List<OutboxEntity>): PushResult
    suspend fun pullAndApply(db: BridgeDb)
}

@Singleton
class RemoteSyncApi @Inject constructor(
    private val api: ApiClient,
    private val session: SessionManager,
    private val store: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
) : SyncApi {

    override suspend fun push(batch: List<OutboxEntity>): SyncApi.PushResult {
        val res = api.push(batch.map { MutationDto(it.id, it.entity, it.entityId, it.op, api.json.decodeFromString<JsonObject>(it.patch), it.createdAt) })
        return SyncApi.PushResult(res.accepted.toSet(), res.rejected)
    }

    override suspend fun pullAndApply(db: BridgeDb) {
        do {
            val page = api.pull(session.cursor())
            db.withTransaction { apply(db, page) }
            session.setCursor(page.cursor)
        } while (page.hasMore)
    }

    private suspend fun apply(db: BridgeDb, page: PullResponse) {
        val dao = db.pull()
        dao.removeGoals(page.removed.goals)                              // cascades to that goal's tasks
        dao.goals(page.goals.map {
            GoalEntity(it.id, it.label, it.statement, it.startDate, it.deadline, it.status, it.sourceAllianceId, it.colorIndex, it.updatedAt, it.routine, it.realityNote,
                it.feasibility?.let { f -> api.json.encodeToString(app.thebridge.data.remote.Feasibility.serializer(), f) })
        })
        // A task or completion can arrive after its parent was deleted on another device; skip instead of breaking the foreign key.
        val goals = dao.knownGoals(page.tasks.map { it.goalId }.distinct()).toSet()
        dao.tasks(page.tasks.filter { it.goalId in goals }.map {
            TaskEntity(it.id, it.goalId, it.title, it.priority, it.timeOfDay, it.minutes, it.rationale, it.notes, it.origin, it.sharedTaskId, it.position, it.deletedAt, it.updatedAt)
        })
        page.removed.completions.forEach { dao.removeCompletion(it.taskId, it.localDate) }
        db.journal().delete(page.removedJournal)
        db.journal().upsert(page.journal.map { JournalEntity(it.id, it.goalId, it.localDate, it.mood, it.body, it.version, it.version, it.updatedAt, it.conflictOf) })
        // Coach conversation from the account's other devices
        db.chat().delete(page.removedChat)
        db.chat().upsert(page.chat.map {
            app.thebridge.data.local.ChatMessageEntity(it.id, it.conversation, it.role, it.text, it.status,
                it.suggestion?.let { s -> api.json.encodeToString(app.thebridge.data.remote.SuggestionDto.serializer(), s) }, it.suggestionState, it.createdAt)
        })
        // Synced settings that the app shows locally
        page.settings?.get("coachReadsJournal")?.let { v ->
            val on = (v as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
            store.edit { it[androidx.datastore.preferences.core.booleanPreferencesKey("coach_reads_journal")] = on }
        }
        val tasks = dao.knownTasks(page.completions.map { it.taskId }.distinct()).toSet()
        dao.completions(page.completions.filter { it.taskId in tasks }.map { CompletionEntity(it.taskId, it.localDate, it.completedAt) })
    }
}
