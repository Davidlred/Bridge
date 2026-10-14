package app.thebridge.data

import android.content.Context
import androidx.room.withTransaction
import app.thebridge.data.local.BridgeDb
import app.thebridge.data.local.JournalEntity
import app.thebridge.data.local.OutboxEntity
import app.thebridge.data.sync.SyncWorker
import app.thebridge.util.UuidV7
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** A journal entry and, when two devices edited it, the other version waiting to be picked. */
data class JournalItem(val entry: JournalEntity, val conflict: JournalEntity?) {
    /** "Lock it in" counts as journaled only when there is text or a mood. */
    val written: Boolean get() = entry.body.isNotBlank() || entry.mood != null
}

/** Reflections, one per goal per local day. Saved locally first (with an outbox record), synced later. */
@Singleton
class JournalRepository @Inject constructor(@ApplicationContext private val ctx: Context, private val db: BridgeDb) {
    companion object { const val MAX_LENGTH = 10_000; const val COUNTER_FROM = 9_000 }

    fun entries(): Flow<List<JournalItem>> = combine(db.journal().all(), db.journal().conflicts()) { all, conflicts ->
        all.map { e -> JournalItem(e, conflicts.firstOrNull { it.conflictOf == e.id }) }
    }

    suspend fun current(goalId: String?, date: String): JournalEntity? = db.journal().current(goalId, date)

    /** Autosave: row + outbox in one transaction. Empty text and no mood still saves (so clearing an entry syncs). */
    suspend fun save(goalId: String?, date: String, mood: Int?, body: String, resolve: Boolean = false): JournalEntity {
        val text = body.take(MAX_LENGTH)
        var saved: JournalEntity? = null
        db.withTransaction {
            val existing = db.journal().current(goalId, date)
            val row = (existing ?: JournalEntity(UuidV7.next(), goalId, date)).copy(mood = mood, body = text, updatedAt = System.currentTimeMillis())
            db.journal().upsert(row)
            if (resolve) db.journal().deleteConflicts(row.id)
            db.journal().enqueue(OutboxEntity(UuidV7.next(), "journal", row.id, "upsert", buildJsonObject {
                put("goalId", goalId); put("localDate", date); put("mood", mood); put("body", text); put("baseVersion", row.syncedVersion)
                if (resolve) put("resolve", true)
            }.toString()))
            saved = row
        }
        SyncWorker.kick(ctx)
        return saved!!
    }

    /** "Two versions of this entry": keep [body]/[mood] (either version, or a merge) and drop the other copy. */
    suspend fun resolve(entry: JournalEntity, mood: Int?, body: String) = save(entry.goalId, entry.localDate, mood, body, resolve = true)
}
