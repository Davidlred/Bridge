import Foundation
import SwiftData
import UIKit

/// A task on today's plan.
struct PlanTask: Identifiable, Equatable {
    let id: UUID
    let title: String
    let priority: String
    let timeOfDay: String?
    let notes: String?
    let rationale: String?
    let origin: String
    let done: Bool
    var isAI: Bool { origin == "ai" }
    var readOnly: Bool { origin == "shared" }
}

struct GoalImage: Equatable { let status: String; let url: String?; let withFace: Bool }

/// One active goal with today's plan and progress. Built from the local store only.
struct GoalState: Identifiable, Equatable {
    let id: UUID
    let label: String
    let statement: String
    let startDate: String
    let deadline: String
    let colorIndex: Int
    let routine: String?
    let realityNote: String?
    let feasibility: Feasibility?
    let tasks: [PlanTask]
    let progress: GoalProgress
    let images: [String: GoalImage]
    /// Today's reflection for this goal has text or a mood ("Locked in.").
    var journaledToday = false
}

struct NewTask { var title: String; var priority = "Medium"; var timeOfDay: String?; var minutes: Int?; var rationale: String?; var notes: String?; var ai = false }

/// Goals, tasks and completions. Every write puts the row and its outbox record in the same save,
/// then nudges sync and reminders; the UI only ever reads the local store.
@MainActor @Observable
final class GoalStore {
    static let freeGoalLimit = 3
    private static let priorityOrder = ["High": 0, "Medium": 1, "Low": 2]

    private(set) var goals: [GoalState] = []
    @ObservationIgnored private var ctx: ModelContext { Persistence.container.mainContext }

    init() {
        reload()
        // A sync pull writes through its own context; re-read when it lands.
        NotificationCenter.default.addObserver(forName: .syncApplied, object: nil, queue: .main) { [weak self] _ in MainActor.assumeIsolated { self?.reload() } }
    }

    /// Re-reads the store. Called after every local write, sync pull and day change.
    func reload() {
        let today = LocalDay.today()
        let active = (try? ctx.fetch(FetchDescriptor<GoalEntity>(predicate: #Predicate { $0.status == "active" }, sortBy: [SortDescriptor(\.startDate)]))) ?? []
        let completions = (try? ctx.fetch(FetchDescriptor<CompletionEntity>())) ?? []
        let doneOn = Dictionary(grouping: completions, by: \.taskId).mapValues { Set($0.map(\.localDate)) }
        let images = (try? ctx.fetch(FetchDescriptor<GoalImageEntity>())) ?? []
        let journaled = Set(((try? ctx.fetch(FetchDescriptor<JournalEntity>(predicate: #Predicate { $0.localDate == today && $0.conflictOf == nil }))) ?? [])
            .filter { !$0.body.isEmpty || $0.mood != nil }.compactMap(\.goalId))
        goals = active.sorted { $0.id.uuidString < $1.id.uuidString }.map { g in
            let live = g.tasks.filter { $0.deletedAt == nil }
            let created = Dictionary(uniqueKeysWithValues: live.map { ($0.id, Progress.createdDay($0.id, start: g.startDate)) })
            let tasks = live.sorted { (Self.priorityOrder[$0.priority] ?? 1, $0.position) < (Self.priorityOrder[$1.priority] ?? 1, $1.position) }
                .map { PlanTask(id: $0.id, title: $0.title, priority: $0.priority, timeOfDay: $0.timeOfDay, notes: $0.notes, rationale: $0.rationale, origin: $0.origin, done: doneOn[$0.id]?.contains(today) == true) }
            return GoalState(
                id: g.id, label: g.label, statement: g.statement, startDate: g.startDate, deadline: g.deadline, colorIndex: g.colorIndex,
                routine: g.routine, realityNote: g.realityNote, feasibility: g.feasibilityJSON.flatMap { try? JSONDecoder().decode(Feasibility.self, from: $0) },
                tasks: tasks,
                progress: Progress.of(start: g.startDate, deadline: g.deadline, today: today, taskCreated: created, completions: doneOn.filter { created[$0.key] != nil }),
                images: Dictionary(uniqueKeysWithValues: images.filter { $0.goalId == g.id }.map { ($0.kind, GoalImage(status: $0.status, url: $0.url, withFace: $0.withFace)) }),
                journaledToday: journaled.contains(g.id)
            )
        }
    }

    var lastRoutine: String? { (try? ctx.fetch(FetchDescriptor<GoalEntity>(sortBy: [SortDescriptor(\.startDate, order: .reverse)])))?.first?.routine }

    // MARK: Writes

    private func enqueue(_ entity: String, _ id: UUID, _ op: String, _ patch: [String: Any]) {
        let data = (try? JSONSerialization.data(withJSONObject: patch)) ?? Data("{}".utf8)
        ctx.insert(OutboxEntity(entity: entity, entityId: id, op: op, patch: data))
    }

    private func commit() {
        try? ctx.save()
        reload()
        Task { await SyncEngine.shared.mutated() }
        Task { await NotificationScheduler.shared.reschedule() }
    }

    func createGoal(id: UUID, label: String, statement: String, deadline: String, routine: String?, feasibility: Feasibility?, tasks: [NewTask]) {
        let start = LocalDay.today()
        let colour = goals.count % 3
        let g = GoalEntity(id: id, label: label.trimmingCharacters(in: .whitespaces), statement: statement.trimmingCharacters(in: .whitespaces), startDate: start, deadline: deadline, colorIndex: colour)
        g.routine = routine; g.feasibilityJSON = try? feasibility.map { try JSONEncoder().encode($0) }
        ctx.insert(g)
        var patch: [String: Any] = ["label": g.label, "statement": g.statement, "startDate": start, "deadline": deadline, "colorIndex": colour, "routine": routine ?? NSNull()]
        if let feasibility, let f = try? JSONSerialization.jsonObject(with: JSONEncoder().encode(feasibility)) { patch["feasibility"] = f }
        enqueue("goal", id, "upsert", patch)
        for (i, t) in tasks.filter({ !$0.title.trimmingCharacters(in: .whitespaces).isEmpty }).enumerated() {
            let row = TaskEntity(id: UUIDv7.next(), title: t.title.trimmingCharacters(in: .whitespaces), priority: t.priority, origin: t.ai ? "ai" : "user", position: i)
            row.timeOfDay = t.timeOfDay; row.minutes = t.minutes; row.rationale = t.rationale; row.notes = t.notes; row.goal = g
            ctx.insert(row)
            enqueue("task", row.id, "upsert", ["goalId": id.uuidString.lowercased(), "title": row.title, "priority": row.priority, "timeOfDay": row.timeOfDay ?? NSNull(),
                                                "minutes": row.minutes ?? NSNull(), "rationale": row.rationale ?? NSNull(), "notes": row.notes ?? NSNull(), "origin": row.origin, "position": i])
        }
        commit()
    }

    private func goalRow(_ id: UUID) -> GoalEntity? { try? ctx.fetch(FetchDescriptor<GoalEntity>(predicate: #Predicate { $0.id == id })).first }
    private func taskRow(_ id: UUID) -> TaskEntity? { try? ctx.fetch(FetchDescriptor<TaskEntity>(predicate: #Predicate { $0.id == id })).first }

    /// Only the fields passed are changed and sent. The deadline is whatever the user picked.
    func updateGoal(_ id: UUID, label: String? = nil, deadline: String? = nil, realityNote: String? = nil, feasibility: Feasibility? = nil) {
        guard let g = goalRow(id) else { return }
        var patch: [String: Any] = [:]
        if let label { g.label = label.trimmingCharacters(in: .whitespaces); patch["label"] = g.label }
        if let deadline { g.deadline = deadline; patch["deadline"] = deadline }
        if let realityNote { g.realityNote = realityNote; patch["realityNote"] = realityNote }
        if let feasibility, let data = try? JSONEncoder().encode(feasibility) { g.feasibilityJSON = data; patch["feasibility"] = try? JSONSerialization.jsonObject(with: data) }
        guard !patch.isEmpty else { return }
        g.updatedAt = .now
        enqueue("goal", id, "upsert", patch)
        commit()
    }

    /// Archived goals leave the daily plan; their images, journal and history are kept.
    func archive(_ id: UUID) {
        guard let g = goalRow(id) else { return }
        g.status = "archived"; g.updatedAt = .now
        enqueue("goal", id, "upsert", ["status": "archived"])
        commit()
    }

    func addTask(goalId: UUID, title: String, notes: String, priority: String, time: String) {
        guard let g = goalRow(goalId) else { return }
        let row = TaskEntity(id: UUIDv7.next(), title: title.trimmingCharacters(in: .whitespaces), priority: priority, origin: "user", position: (g.tasks.map(\.position).max() ?? -1) + 1)
        row.notes = notes.isEmpty ? nil : notes; row.timeOfDay = time.isEmpty ? nil : time; row.goal = g
        ctx.insert(row)
        enqueue("task", row.id, "upsert", ["goalId": goalId.uuidString.lowercased(), "title": row.title, "priority": priority, "timeOfDay": row.timeOfDay ?? NSNull(), "notes": row.notes ?? NSNull(), "origin": "user", "position": row.position])
        commit()
    }

    func updateTask(_ id: UUID, title: String, notes: String, priority: String, time: String) {
        guard let t = taskRow(id), t.origin != "shared" else { return }      // shared-plan tasks are read-only for members
        var patch: [String: Any] = [:]
        let title = title.trimmingCharacters(in: .whitespaces), notes = notes.isEmpty ? nil : notes, time = time.isEmpty ? nil : time
        if title != t.title { t.title = title; patch["title"] = title }
        if notes != t.notes { t.notes = notes; patch["notes"] = notes ?? NSNull() }
        if priority != t.priority { t.priority = priority; patch["priority"] = priority }
        if time != t.timeOfDay { t.timeOfDay = time; patch["timeOfDay"] = time ?? NSNull() }
        guard !patch.isEmpty else { return }
        t.updatedAt = .now
        enqueue("task", id, "upsert", patch)
        commit()
    }

    func deleteTask(_ id: UUID) {
        guard let t = taskRow(id), t.origin != "shared" else { return }
        t.deletedAt = .now
        enqueue("task", id, "delete", [:])
        commit()
    }

    /// Ticks a task for the current local day (which closes at 03:00).
    /// A coach suggestion accepted with "Add to plan": an AI task with its reason.
    func addSuggestedTask(goalId: UUID, _ s: Suggestion) {
        guard let g = goalRow(goalId) else { return }
        let row = TaskEntity(id: UUIDv7.next(), title: String(s.title.prefix(120)), priority: s.priority, origin: "ai", position: (g.tasks.map(\.position).max() ?? -1) + 1)
        row.timeOfDay = s.timeOfDay; row.rationale = s.rationale.isEmpty ? nil : String(s.rationale.prefix(200)); row.goal = g
        ctx.insert(row)
        enqueue("task", row.id, "upsert", ["goalId": goalId.uuidString.lowercased(), "title": row.title, "priority": row.priority, "timeOfDay": row.timeOfDay ?? NSNull(),
                                            "rationale": row.rationale ?? NSNull(), "origin": "ai", "position": row.position])
        commit()
    }

    /// Synced user settings (e.g. coachReadsJournal).
    func saveSetting(userId: UUID, _ key: String, _ value: Bool) {
        enqueue("settings", userId, "upsert", [key: value])
        commit()
    }

    /// Suggestion state and deletes of coach messages sync to the account's other devices.
    func syncChat(_ id: UUID, op: String, patch: [String: Any] = [:]) {
        enqueue("chat", id, op, patch)
        try? ctx.save()
        Task { await SyncEngine.shared.mutated() }
    }

    // MARK: Journal

    func journalEntry(goalId: UUID?, date: String) -> JournalEntity? {
        let rows = (try? ctx.fetch(FetchDescriptor<JournalEntity>(predicate: #Predicate { $0.localDate == date && $0.conflictOf == nil }))) ?? []
        return rows.first { $0.goalId == goalId }
    }
    func journalHistory() -> [JournalEntity] {
        (try? ctx.fetch(FetchDescriptor<JournalEntity>(predicate: #Predicate { $0.conflictOf == nil }, sortBy: [SortDescriptor(\.localDate, order: .reverse)]))) ?? []
    }
    func journalConflict(of id: UUID) -> JournalEntity? {
        try? ctx.fetch(FetchDescriptor<JournalEntity>(predicate: #Predicate { $0.conflictOf == id })).first
    }

    /// Autosave: row + outbox in one save. `resolve` drops the other device's copy after the user picks.
    func saveJournal(goalId: UUID?, date: String, mood: Int?, body: String, resolve: Bool = false) {
        let text = String(body.prefix(10_000))
        let row = journalEntry(goalId: goalId, date: date) ?? { let e = JournalEntity(id: UUIDv7.next(), goalId: goalId, localDate: date); ctx.insert(e); return e }()
        row.mood = mood; row.body = text; row.updatedAt = .now
        if resolve, let other = journalConflict(of: row.id) { ctx.delete(other) }
        var patch: [String: Any] = ["goalId": goalId?.uuidString.lowercased() ?? NSNull(), "localDate": date, "mood": mood ?? NSNull(), "body": text, "baseVersion": row.syncedVersion]
        if resolve { patch["resolve"] = true }
        enqueue("journal", row.id, "upsert", patch)
        try? ctx.save()
        reload()
        Task { await SyncEngine.shared.mutated() }
    }

    func setDone(_ id: UUID, _ done: Bool) {
        let day = LocalDay.today()
        let key = CompletionEntity.key(id, day)
        let existing = try? ctx.fetch(FetchDescriptor<CompletionEntity>(predicate: #Predicate { $0.key == key })).first
        if done, existing == nil { ctx.insert(CompletionEntity(taskId: id, localDate: day)) }
        if !done, let existing { ctx.delete(existing) }
        enqueue("completion", id, done ? "complete" : "uncomplete", ["localDate": day])
        commit()
    }

    // MARK: Images

    func saveImages(_ goalId: UUID, _ dtos: [GoalImageDTO]) {
        for d in dtos where d.status != "none" {
            let key = "\(goalId.uuidString)|\(d.kind)"
            if let row = try? ctx.fetch(FetchDescriptor<GoalImageEntity>(predicate: #Predicate { $0.key == key })).first {
                row.status = d.status; row.url = d.url; row.withFace = d.withFace ?? false
            } else {
                ctx.insert(GoalImageEntity(goalId: goalId, kind: d.kind, status: d.status, url: d.url, withFace: d.withFace ?? false))
            }
        }
        try? ctx.save()
        reload()
    }

    func images(for goalId: UUID) -> [String: GoalImage] {
        let rows = (try? ctx.fetch(FetchDescriptor<GoalImageEntity>(predicate: #Predicate { $0.goalId == goalId }))) ?? []
        return Dictionary(uniqueKeysWithValues: rows.map { ($0.kind, GoalImage(status: $0.status, url: $0.url, withFace: $0.withFace)) })
    }
}

/// Downscales to at most 1024px (keeps uploads small and drops metadata such as location) and encodes JPEG.
enum PhotoPrep {
    static func jpeg(_ data: Data, maxSide: CGFloat = 1024) -> Data? {
        guard let image = UIImage(data: data) else { return nil }
        let scale = min(1, maxSide / max(image.size.width, image.size.height))
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let format = UIGraphicsImageRendererFormat.default(); format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }.jpegData(compressionQuality: 0.85)
    }
}
