package app.thebridge.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// Local source of truth. Ids are UUIDv7 generated on device.

@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey val id: String,
    val label: String,
    val statement: String,
    val startDate: String,
    val deadline: String,          // picked by the user; AI never changes it
    val status: String = "active",
    val sourceAllianceId: String? = null,
    val colorIndex: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    val routine: String? = null,       // onboarding step 2, shown in Reality Check
    val realityNote: String? = null,   // "Why I started"
    val feasibility: String? = null,   // JSON {verdict, reason, suggestedDeadline}
    val category: String? = null,      // from the plan; groups Community by kind of goal
)

/** Future Self / If nothing changes. The URL is a signed link; the image itself is cached on disk by a stable key. */
@Entity(tableName = "goal_images", primaryKeys = ["goalId", "kind"])
data class GoalImageEntity(val goalId: String, val kind: String, val status: String, val url: String?, val withFace: Boolean, val updatedAt: Long = System.currentTimeMillis())

@Entity(tableName = "tasks", foreignKeys = [ForeignKey(GoalEntity::class, ["id"], ["goalId"], onDelete = ForeignKey.CASCADE)], indices = [Index("goalId")])
data class TaskEntity(
    @PrimaryKey val id: String,
    val goalId: String,
    val title: String,
    val priority: String,          // High | Medium | Low
    val timeOfDay: String? = null,
    val minutes: Int? = null,
    val rationale: String? = null,
    val notes: String? = null,
    val origin: String = "user",   // ai | user | shared (shared = read-only for members)
    val sharedTaskId: String? = null,
    val position: Int = 0,
    val deletedAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "completions", primaryKeys = ["taskId", "localDate"])
data class CompletionEntity(val taskId: String, val localDate: String, val completedAt: Long = System.currentTimeMillis())

/**
 * One reflection per goal per local day. [syncedVersion] is the server version this device last saw (sent as baseVersion).
 * A row with [conflictOf] set is the other device's version of an entry, kept until the user picks one.
 */
@Entity(tableName = "journal", indices = [Index(value = ["goalId", "localDate"]), Index("conflictOf")])
data class JournalEntity(
    @PrimaryKey val id: String,
    val goalId: String?,
    val localDate: String,
    val mood: Int? = null,
    val body: String = "",
    val version: Int = 1,
    val syncedVersion: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    val conflictOf: String? = null,
)

/** Coach conversation, kept on this device. [conversation] groups messages; "New conversation" starts a new one. */
@Entity(tableName = "chat", indices = [Index("conversation")])
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val conversation: String,
    val role: String,                  // user | coach
    val text: String,
    val status: String = "sent",       // sent | sending | failed | streaming | interrupted | done
    val suggestion: String? = null,    // JSON {title, rationale, priority, timeOfDay}
    val suggestionState: String? = null, // pending | added | dismissed
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface JournalDao {
    @Query("SELECT * FROM journal WHERE conflictOf IS NULL ORDER BY localDate DESC") fun all(): Flow<List<JournalEntity>>
    @Query("SELECT * FROM journal WHERE conflictOf IS NOT NULL") fun conflicts(): Flow<List<JournalEntity>>
    @Query("SELECT * FROM journal WHERE localDate = :date AND conflictOf IS NULL AND ((:goalId IS NULL AND goalId IS NULL) OR goalId = :goalId) LIMIT 1")
    suspend fun current(goalId: String?, date: String): JournalEntity?
    @Query("SELECT * FROM journal WHERE id = :id") suspend fun get(id: String): JournalEntity?
    @Upsert suspend fun upsert(e: JournalEntity)
    @Upsert suspend fun upsert(e: List<JournalEntity>)
    @Query("DELETE FROM journal WHERE id IN (:ids)") suspend fun delete(ids: List<String>)
    @Query("DELETE FROM journal WHERE conflictOf = :id") suspend fun deleteConflicts(id: String)
    @Insert suspend fun enqueue(m: OutboxEntity)
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat ORDER BY createdAt") fun all(): Flow<List<ChatMessageEntity>>
    @Upsert suspend fun upsert(m: List<ChatMessageEntity>)
    @Query("DELETE FROM chat WHERE id IN (:ids)") suspend fun delete(ids: List<String>)
    @Insert suspend fun enqueue(o: OutboxEntity)
    @Query("SELECT * FROM chat WHERE id = :id") suspend fun get(id: String): ChatMessageEntity?
    @Upsert suspend fun upsert(m: ChatMessageEntity)
    @Query("UPDATE chat SET text = :text, status = :status WHERE id = :id") suspend fun update(id: String, text: String, status: String)
    @Query("SELECT * FROM chat WHERE conversation = :conversation ORDER BY createdAt DESC LIMIT :limit") suspend fun recent(conversation: String, limit: Int): List<ChatMessageEntity>
    @Query("DELETE FROM chat WHERE id = :id") suspend fun delete(id: String)
}

/** Server responses kept for offline reading: the Community feed per goal and the user's alliances. */
@Entity(tableName = "social_cache")
data class SocialCacheEntity(@PrimaryKey val cacheKey: String, val json: String, val updatedAt: Long = System.currentTimeMillis())

/**
 * Alliance chat. [id] is the client id (the server dedupes by it). Messages written offline stay "queued" and
 * send when the connection is back; [status] is sent | queued | failed.
 */
@Entity(tableName = "alliance_messages", indices = [Index("allianceId")])
data class AllianceMessageEntity(
    @PrimaryKey val id: String,
    val allianceId: String,
    val serverId: String? = null,
    val userId: String? = null,
    val name: String? = null,
    val body: String,
    val mine: Boolean,
    val createdAt: Long = System.currentTimeMillis(),
    val status: String = "sent",
)

@Dao
interface SocialDao {
    @Query("SELECT * FROM social_cache WHERE cacheKey = :key") fun cached(key: String): Flow<SocialCacheEntity?>
    @Upsert suspend fun cache(e: SocialCacheEntity)
    @Query("SELECT * FROM alliance_messages WHERE allianceId = :allianceId ORDER BY createdAt") fun messages(allianceId: String): Flow<List<AllianceMessageEntity>>
    @Query("SELECT * FROM alliance_messages WHERE status = 'queued' ORDER BY createdAt") suspend fun queued(): List<AllianceMessageEntity>
    @Upsert suspend fun upsert(m: AllianceMessageEntity)
    @Upsert suspend fun upsert(m: List<AllianceMessageEntity>)
    @Query("DELETE FROM alliance_messages WHERE serverId = :serverId") suspend fun removeByServerId(serverId: String)
    @Query("DELETE FROM alliance_messages WHERE id = :id") suspend fun remove(id: String)
    /** Server copies replace the cache for one alliance; unsent messages are kept. */
    @Transaction
    suspend fun replace(allianceId: String, rows: List<AllianceMessageEntity>) {
        clearSent(allianceId); upsert(rows)
    }
    @Query("DELETE FROM alliance_messages WHERE allianceId = :allianceId AND status = 'sent'") suspend fun clearSent(allianceId: String)
}

/** This install's Olm account (identity and one-time keys), as an encrypted pickle. One row. */
@Entity(tableName = "olm_account")
data class OlmAccountEntity(@PrimaryKey val id: Int = 0, val pickle: String, val registered: Boolean = false)

/** One Olm session per device we talk to (the other person's devices and our own other devices). */
@Entity(tableName = "olm_sessions", indices = [Index("peerUserId")])
data class OlmSessionEntity(@PrimaryKey val deviceId: String, val peerUserId: String, val curve25519: String, val ed25519: String, val pickle: String, val updatedAt: Long = System.currentTimeMillis())

/** Decrypted direct messages, kept only on this device (the database is SQLCipher-encrypted). [id] is the sender's client id. */
@Entity(tableName = "dm_messages", indices = [Index("peerUserId")])
data class DmMessageEntity(
    @PrimaryKey val id: String,
    val peerUserId: String,
    val mine: Boolean,
    val body: String,
    val createdAt: Long = System.currentTimeMillis(),
    val status: String = "sent",            // sent | queued | failed | undecryptable
    val read: Boolean = false,
)

/** What we know about the other person: name, and the device keys they had when we last verified the safety number. */
@Entity(tableName = "dm_peers")
data class DmPeerEntity(@PrimaryKey val userId: String, val name: String, val verifiedKeys: String? = null, val currentKeys: String? = null)

@Dao
interface DmDao {
    @Query("SELECT * FROM olm_account WHERE id = 0") suspend fun account(): OlmAccountEntity?
    @Upsert suspend fun saveAccount(a: OlmAccountEntity)
    @Query("SELECT * FROM olm_sessions WHERE deviceId = :deviceId") suspend fun session(deviceId: String): OlmSessionEntity?
    @Upsert suspend fun saveSession(s: OlmSessionEntity)
    @Query("DELETE FROM olm_sessions WHERE deviceId = :deviceId") suspend fun dropSession(deviceId: String)
    @Query("SELECT * FROM dm_messages WHERE peerUserId = :peer ORDER BY createdAt") fun thread(peer: String): Flow<List<DmMessageEntity>>
    @Query("SELECT * FROM dm_messages ORDER BY createdAt") fun all(): Flow<List<DmMessageEntity>>
    @Query("SELECT COUNT(*) FROM dm_messages") fun count(): Flow<Int>
    @Query("SELECT * FROM dm_messages WHERE status = 'queued' ORDER BY createdAt") suspend fun queued(): List<DmMessageEntity>
    @Query("SELECT * FROM dm_messages WHERE id = :id") suspend fun message(id: String): DmMessageEntity?
    @Upsert suspend fun saveMessage(m: DmMessageEntity)
    @Query("DELETE FROM dm_messages WHERE id = :id") suspend fun deleteMessage(id: String)
    @Query("UPDATE dm_messages SET read = 1 WHERE peerUserId = :peer AND NOT mine") suspend fun markRead(peer: String)
    @Query("SELECT * FROM dm_peers") fun peers(): Flow<List<DmPeerEntity>>
    @Query("SELECT * FROM dm_peers WHERE userId = :id") suspend fun peer(id: String): DmPeerEntity?
    @Upsert suspend fun savePeer(p: DmPeerEntity)
}

@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val id: String,    // idempotency key
    val entity: String,            // goal | task | completion | settings
    val entityId: String,
    val op: String,                // upsert | delete | complete | uncomplete
    val patch: String,             // JSON of changed fields
    val createdAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
)

@Dao
interface GoalDao {
    @Query("SELECT * FROM goals WHERE status = 'active' ORDER BY id") fun active(): Flow<List<GoalEntity>>
    @Query("SELECT * FROM goals WHERE status = 'paused' ORDER BY id") fun paused(): Flow<List<GoalEntity>>
    @Query("SELECT * FROM goals WHERE status IN ('active', 'paused') ORDER BY id") suspend fun activeOrPaused(): List<GoalEntity>
    @Query("SELECT * FROM goals WHERE id = :id") suspend fun get(id: String): GoalEntity?
    @Query("SELECT COUNT(*) FROM goals WHERE status = 'active'") suspend fun activeCount(): Int
    @Query("SELECT * FROM goals ORDER BY id DESC LIMIT 1") suspend fun latest(): GoalEntity?
    @Upsert suspend fun upsert(goal: GoalEntity)
    @Upsert suspend fun upsert(goals: List<GoalEntity>)
    @Query("DELETE FROM goals WHERE id IN (:ids)") suspend fun delete(ids: List<String>)
    @Insert suspend fun enqueue(m: OutboxEntity)

    @Query("SELECT * FROM tasks WHERE goalId IN (SELECT id FROM goals WHERE status = 'active') ORDER BY position")
    fun activeTasks(): Flow<List<TaskEntity>>
    @Query("SELECT c.* FROM completions c JOIN tasks t ON t.id = c.taskId JOIN goals g ON g.id = t.goalId WHERE g.status = 'active'")
    fun activeCompletions(): Flow<List<CompletionEntity>>

    @Query("SELECT * FROM goal_images") fun images(): Flow<List<GoalImageEntity>>
    @Query("SELECT * FROM goal_images WHERE goalId = :goalId") fun images(goalId: String): Flow<List<GoalImageEntity>>
    @Upsert suspend fun upsertImages(rows: List<GoalImageEntity>)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE goalId = :goalId AND deletedAt IS NULL ORDER BY position")
    fun tasks(goalId: String): Flow<List<TaskEntity>>

    @Upsert suspend fun upsert(task: TaskEntity)
    @Upsert suspend fun upsert(tasks: List<TaskEntity>)
    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun get(id: String): TaskEntity?
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM tasks WHERE goalId = :goalId") suspend fun nextPosition(goalId: String): Int
    @Insert suspend fun enqueue(m: OutboxEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun complete(c: CompletionEntity)
    @Query("DELETE FROM completions WHERE taskId = :taskId AND localDate = :localDate") suspend fun uncomplete(taskId: String, localDate: String)

    /** Row + outbox in one transaction, so a crash can never lose a change. */
    @Transaction
    suspend fun toggle(taskId: String, localDate: String, done: Boolean, outboxId: String) {
        if (done) complete(CompletionEntity(taskId, localDate)) else uncomplete(taskId, localDate)
        enqueue(OutboxEntity(outboxId, "completion", taskId, if (done) "complete" else "uncomplete", """{"localDate":"$localDate"}"""))
    }
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox ORDER BY createdAt LIMIT :limit") suspend fun batch(limit: Int = 200): List<OutboxEntity>
    @Query("DELETE FROM outbox WHERE id IN (:ids)") suspend fun remove(ids: List<String>)
    @Query("UPDATE outbox SET attempts = attempts + 1 WHERE id IN (:ids)") suspend fun bump(ids: List<String>)
    @Query("SELECT COUNT(*) FROM outbox") fun count(): Flow<Int>
    @Query("SELECT COUNT(*) FROM outbox") suspend fun countNow(): Int
}

/** Applies a page of server changes. The caller wraps one page in a single transaction. */
@Dao
interface PullDao {
    @Upsert suspend fun goals(rows: List<GoalEntity>)
    @Query("DELETE FROM goals WHERE id IN (:ids)") suspend fun removeGoals(ids: List<String>)
    @Query("SELECT id FROM goals WHERE id IN (:ids)") suspend fun knownGoals(ids: List<String>): List<String>
    @Upsert suspend fun tasks(rows: List<TaskEntity>)
    @Query("SELECT id FROM tasks WHERE id IN (:ids)") suspend fun knownTasks(ids: List<String>): List<String>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun completions(rows: List<CompletionEntity>)
    @Query("DELETE FROM completions WHERE taskId = :taskId AND localDate = :localDate") suspend fun removeCompletion(taskId: String, localDate: String)
}

@Database(
    entities = [GoalEntity::class, TaskEntity::class, CompletionEntity::class, JournalEntity::class, OutboxEntity::class, GoalImageEntity::class, ChatMessageEntity::class,
        SocialCacheEntity::class, AllianceMessageEntity::class, OlmAccountEntity::class, OlmSessionEntity::class, DmMessageEntity::class, DmPeerEntity::class],
    version = 5, exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5)],
)
abstract class BridgeDb : RoomDatabase() {
    abstract fun goals(): GoalDao
    abstract fun tasks(): TaskDao
    abstract fun outbox(): OutboxDao
    abstract fun pull(): PullDao
    abstract fun journal(): JournalDao
    abstract fun chat(): ChatDao
    abstract fun social(): SocialDao
    abstract fun dm(): DmDao
}

object LocalDay {
    /** The user's day closes at 03:00 local. */
    fun today(now: LocalDateTime = LocalDateTime.now()): String = now.minusHours(3).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
}
