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

@Model final class JournalEntity {
    @Attribute(.unique) var id: UUID
    var goalId: UUID?
    var localDate: String
    var mood: Int?
    var body: String               // protected by iOS Data Protection (complete) at rest
    var version: Int
    var syncedVersion: Int
    var updatedAt: Date
    init(id: UUID, goalId: UUID?, localDate: String) {
        self.id = id; self.goalId = goalId; self.localDate = localDate; self.body = ""; self.version = 1; self.syncedVersion = 0; self.updatedAt = .now
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
    static let models: [any PersistentModel.Type] = [GoalEntity.self, TaskEntity.self, CompletionEntity.self, JournalEntity.self, OutboxEntity.self, GoalImageEntity.self]
    static let container: ModelContainer = {
        do { return try ModelContainer(for: Schema(models)) } catch { fatalError("Could not open the local database: \(error)") }
    }()

    /// Log out / delete account / a different user signing in: start from an empty database.
    static func wipe() throws {
        let ctx = ModelContext(container)
        try ctx.delete(model: OutboxEntity.self); try ctx.delete(model: CompletionEntity.self); try ctx.delete(model: JournalEntity.self)
        try ctx.delete(model: TaskEntity.self); try ctx.delete(model: GoalEntity.self); try ctx.delete(model: GoalImageEntity.self)
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
