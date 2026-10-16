import Foundation
import SwiftData

// Local source of truth. Ids are UUIDv7 generated on device, so offline creates never need the server.

@Model final class GoalEntity {
    @Attribute(.unique) var id: UUID
    var label: String
    var statement: String
    var startDate: String          // yyyy-MM-dd, user's local calendar
    var deadline: String           // picked by the user; AI never changes it
    var status: String             // active | archived | completed
    var sourceAllianceId: UUID?    // set when created from a shared-plan alliance
    var colorIndex: Int
    var updatedAt: Date
    var routine: String?           // onboarding step 2, shown in Reality Check
    var realityNote: String?       // "Why I started"
    var feasibilityJSON: Data?     // {verdict, reason, suggestedDeadline}
    var category: String?          // from the plan; groups Community by kind of goal
    @Relationship(deleteRule: .cascade, inverse: \TaskEntity.goal) var tasks: [TaskEntity] = []

    init(id: UUID, label: String, statement: String, startDate: String, deadline: String, colorIndex: Int) {
        self.id = id; self.label = label; self.statement = statement; self.startDate = startDate
        self.deadline = deadline; self.status = "active"; self.colorIndex = colorIndex; self.updatedAt = .now
    }
}

@Model final class TaskEntity {
    @Attribute(.unique) var id: UUID
    var goal: GoalEntity?
    var title: String
    var priority: String           // High | Medium | Low
    var timeOfDay: String?
    var minutes: Int?
    var rationale: String?
    var notes: String?
    var origin: String             // ai | user | shared
    var sharedTaskId: UUID?        // read-only for members when non-nil
    var position: Int
    var deletedAt: Date?
    var updatedAt: Date

    var isReadOnlyShared: Bool { origin == "shared" }

    init(id: UUID, title: String, priority: String, origin: String, position: Int) {
        self.id = id; self.title = title; self.priority = priority; self.origin = origin
        self.position = position; self.updatedAt = .now
    }
}

@Model final class CompletionEntity {
    /// "<taskId>|<localDate>": one completion per task per local day (iOS 17 has no compound unique constraint).
    @Attribute(.unique) var key: String
    var taskId: UUID
    var localDate: String
    var completedAt: Date
    init(taskId: UUID, localDate: String, completedAt: Date = .now) {
        self.key = Self.key(taskId, localDate); self.taskId = taskId; self.localDate = localDate; self.completedAt = completedAt
    }
    static func key(_ taskId: UUID, _ localDate: String) -> String { "\(taskId.uuidString)|\(localDate)" }
}

/// One reflection per goal per local day. `syncedVersion` is the server version this device last saw (sent as baseVersion).
/// A row with `conflictOf` set is the other device's version of an entry, kept until the user picks one.
@Model final class JournalEntity {
    @Attribute(.unique) var id: UUID
    var goalId: UUID?
    var localDate: String
    var mood: Int?
    var body: String               // protected by iOS Data Protection (complete) at rest
    var version: Int
    var syncedVersion: Int
    var updatedAt: Date
    var conflictOf: UUID?
    init(id: UUID, goalId: UUID?, localDate: String) {
        self.id = id; self.goalId = goalId; self.localDate = localDate; self.body = ""; self.version = 1; self.syncedVersion = 0; self.updatedAt = .now
    }
}

/// Coach conversation, kept on this device. "New conversation" starts a new `conversation` id.
@Model final class ChatMessageEntity {
    @Attribute(.unique) var id: UUID
    var conversation: UUID
    var role: String               // user | coach
    var text: String
    var status: String             // sent | sending | failed | streaming | interrupted | done
    var suggestionJSON: Data?
    var suggestionState: String?   // pending | added | dismissed
    var createdAt: Date
    init(id: UUID = UUIDv7.next(), conversation: UUID, role: String, text: String, status: String, createdAt: Date = .now) {
        self.id = id; self.conversation = conversation; self.role = role; self.text = text; self.status = status; self.createdAt = createdAt
    }
}

/// "Future Self" / "If nothing changes". The URL is a signed link; the bytes are cached on disk by a stable key.
@Model final class GoalImageEntity {
    /// "<goalId>|<kind>"
    @Attribute(.unique) var key: String
    var goalId: UUID
    var kind: String               // future | reality
    var status: String             // running | done | failed
    var url: String?
    var withFace: Bool
    init(goalId: UUID, kind: String, status: String, url: String?, withFace: Bool) {
        self.key = "\(goalId.uuidString)|\(kind)"; self.goalId = goalId; self.kind = kind; self.status = status; self.url = url; self.withFace = withFace
    }
}

/// Server responses kept for offline reading: the Community feed per goal and the user's alliances.
@Model final class SocialCacheEntity {
    @Attribute(.unique) var key: String
    var json: Data
    var updatedAt: Date
    init(key: String, json: Data) { self.key = key; self.json = json; self.updatedAt = .now }
}

/// Alliance chat. `id` is the client id (the server dedupes by it). Messages written offline stay "queued" and send when the
/// connection is back; `status` is sent | queued | failed.
@Model final class AllianceMessageEntity {
    @Attribute(.unique) var id: String
    var allianceId: String
    var serverId: String?
    var userId: String?
    var name: String?
    var body: String
    var mine: Bool
    var createdAt: Date
    var status: String
    init(id: String, allianceId: String, body: String, mine: Bool, status: String = "sent", createdAt: Date = .now) {
        self.id = id; self.allianceId = allianceId; self.body = body; self.mine = mine; self.status = status; self.createdAt = createdAt
    }
}

/// This install's Olm account (identity and one-time keys) as an encrypted pickle. One row.
@Model final class OlmAccountRow {
    @Attribute(.unique) var id: Int
    var pickle: String
    var registered: Bool
    init(pickle: String, registered: Bool) { self.id = 0; self.pickle = pickle; self.registered = registered }
}

/// One Olm session per device we talk to (the other person's devices and our own other devices).
@Model final class OlmSessionRow {
    @Attribute(.unique) var deviceId: String
    var peerUserId: String
    var curve25519: String
    var ed25519: String
    var pickle: String
    var updatedAt: Date
    init(deviceId: String, peerUserId: String, curve25519: String, ed25519: String, pickle: String) {
        self.deviceId = deviceId; self.peerUserId = peerUserId; self.curve25519 = curve25519; self.ed25519 = ed25519; self.pickle = pickle; self.updatedAt = .now
    }
}

/// Decrypted direct messages, kept only on this device. `id` is the sender's client id.
@Model final class DmMessageRow {
    @Attribute(.unique) var id: String
    var peerUserId: String
    var mine: Bool
    var body: String
    var createdAt: Date
    var status: String             // sent | queued | failed | no_devices | undecryptable
    var read: Bool
    init(id: String, peerUserId: String, mine: Bool, body: String, createdAt: Date = .now, status: String = "sent", read: Bool = false) {
        self.id = id; self.peerUserId = peerUserId; self.mine = mine; self.body = body; self.createdAt = createdAt; self.status = status; self.read = read
    }
}

/// The other person: name, and their device keys when we last verified the safety number.
@Model final class DmPeerRow {
    @Attribute(.unique) var userId: String
    var name: String
    var verifiedKeys: String?
    var currentKeys: String?
    init(userId: String, name: String) { self.userId = userId; self.name = name }
}

/// Every local mutation writes one of these in the same transaction.
@Model final class OutboxEntity {
    @Attribute(.unique) var id: UUID   // idempotency key on the server
    var entity: String                 // goal | task | completion | settings
    var entityId: UUID
    var op: String                     // upsert | delete | complete | uncomplete
    var patch: Data                    // JSON of changed fields only
    var createdAt: Date
    var attempts: Int
    init(entity: String, entityId: UUID, op: String, patch: Data) {
        self.id = UUIDv7.next(); self.entity = entity; self.entityId = entityId; self.op = op; self.patch = patch; self.createdAt = .now; self.attempts = 0
    }
}

enum Persistence {
    static let models: [any PersistentModel.Type] = [GoalEntity.self, TaskEntity.self, CompletionEntity.self, JournalEntity.self, OutboxEntity.self, GoalImageEntity.self, ChatMessageEntity.self, SocialCacheEntity.self, AllianceMessageEntity.self,
                                                        OlmAccountRow.self, OlmSessionRow.self, DmMessageRow.self, DmPeerRow.self]
    static let container: ModelContainer = {
        do { return try ModelContainer(for: Schema(models)) } catch { fatalError("Could not open the local database: \(error)") }
    }()

    /// Log out / delete account / a different user signing in: start from an empty database.
    static func wipe() throws {
        let ctx = ModelContext(container)
        try ctx.delete(model: OutboxEntity.self); try ctx.delete(model: CompletionEntity.self); try ctx.delete(model: JournalEntity.self)
        try ctx.delete(model: TaskEntity.self); try ctx.delete(model: GoalEntity.self); try ctx.delete(model: GoalImageEntity.self); try ctx.delete(model: ChatMessageEntity.self)
        try ctx.delete(model: SocialCacheEntity.self); try ctx.delete(model: AllianceMessageEntity.self)
        try ctx.delete(model: OlmAccountRow.self); try ctx.delete(model: OlmSessionRow.self); try ctx.delete(model: DmMessageRow.self); try ctx.delete(model: DmPeerRow.self)
        OlmPickleKey.reset()
        try ctx.save()
        UserDefaults.standard.removeObject(forKey: "syncCursor")
    }

    static func outboxCount() -> Int { (try? ModelContext(container).fetchCount(FetchDescriptor<OutboxEntity>())) ?? 0 }
}

enum LocalDay {
    /// The user's "day" closes at 03:00 local, so late-night completions count for the day they perceive.
    static func today(now: Date = .now, calendar: Calendar = .current) -> String {
        let shifted = calendar.date(byAdding: .hour, value: -3, to: now) ?? now
        let c = calendar.dateComponents([.year, .month, .day], from: shifted)
        return String(format: "%04d-%02d-%02d", c.year!, c.month!, c.day!)
    }
}

/// UUIDv7 (48-bit unix-ms timestamp + random): sortable by creation time.
enum UUIDv7 {
    static func next(now: Date = .now) -> UUID {
        var b = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, 16, &b)
        let ms = UInt64(now.timeIntervalSince1970 * 1000)
        for i in 0..<6 { b[i] = UInt8((ms >> UInt64(8 * (5 - i))) & 0xFF) }
        b[6] = (b[6] & 0x0F) | 0x70
        b[8] = (b[8] & 0x3F) | 0x80
        return UUID(uuid: (b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15]))
    }
}
