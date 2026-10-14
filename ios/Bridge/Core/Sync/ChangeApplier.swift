import Foundation
import SwiftData

/// Writes one page of server changes into the local store. The caller saves the context (one transaction per page).
enum ChangeApplier {
    static func apply(_ page: Changes, in ctx: ModelContext) throws {
        let date = { (ms: Double?) in ms.map { Date(timeIntervalSince1970: $0 / 1000) } }

        for id in page.removed.goals {
            if let g = try goal(id, ctx) { ctx.delete(g) }               // cascades to that goal's tasks
        }
        for g in page.goals {
            let row = try goal(g.id, ctx) ?? {
                let fresh = GoalEntity(id: g.id, label: g.label, statement: g.statement, startDate: g.startDate, deadline: g.deadline, colorIndex: g.colorIndex ?? 0)
                ctx.insert(fresh); return fresh
            }()
            row.label = g.label; row.statement = g.statement; row.startDate = g.startDate; row.deadline = g.deadline
            row.status = g.status; row.sourceAllianceId = g.sourceAllianceId; row.colorIndex = g.colorIndex ?? 0; row.updatedAt = date(g.updatedAt) ?? .now
            row.routine = g.routine; row.realityNote = g.realityNote; row.feasibilityJSON = try g.feasibility.map { try JSONEncoder().encode($0) }
            row.category = g.category
        }
        for t in page.tasks {
            // A task can arrive after its goal was deleted on another device; skip it.
            guard let parent = try goal(t.goalId, ctx) else { continue }
            let id = t.id
            let row = try ctx.fetch(FetchDescriptor<TaskEntity>(predicate: #Predicate { $0.id == id })).first ?? {
                let fresh = TaskEntity(id: t.id, title: t.title, priority: t.priority, origin: t.origin, position: t.position)
                ctx.insert(fresh); return fresh
            }()
            row.goal = parent; row.title = t.title; row.priority = t.priority; row.timeOfDay = t.timeOfDay; row.minutes = t.minutes
            row.rationale = t.rationale; row.notes = t.notes; row.origin = t.origin; row.sharedTaskId = t.sharedTaskId; row.position = t.position
            row.deletedAt = date(t.deletedAt); row.updatedAt = date(t.updatedAt) ?? .now
        }
        for c in page.removed.completions {
            if let row = try completion(c.taskId, c.localDate, ctx) { ctx.delete(row) }
        }
        for id in page.removedJournal ?? [] {
            if let row = try ctx.fetch(FetchDescriptor<JournalEntity>(predicate: #Predicate { $0.id == id })).first { ctx.delete(row) }
        }
        for j in page.journal ?? [] {
            let id = j.id
            let row = try ctx.fetch(FetchDescriptor<JournalEntity>(predicate: #Predicate { $0.id == id })).first ?? {
                let fresh = JournalEntity(id: j.id, goalId: j.goalId, localDate: j.localDate); ctx.insert(fresh); return fresh
            }()
            row.goalId = j.goalId; row.localDate = j.localDate; row.mood = j.mood; row.body = j.body
            row.version = j.version; row.syncedVersion = j.version; row.conflictOf = j.conflictOf
        }
        // Coach conversation from the account's other devices
        for id in page.removedChat ?? [] {
            if let row = try ctx.fetch(FetchDescriptor<ChatMessageEntity>(predicate: #Predicate { $0.id == id })).first { ctx.delete(row) }
        }
        for c in page.chat ?? [] {
            let id = c.id
            let row = try ctx.fetch(FetchDescriptor<ChatMessageEntity>(predicate: #Predicate { $0.id == id })).first ?? {
                let fresh = ChatMessageEntity(id: c.id, conversation: c.conversation, role: c.role, text: c.text, status: c.status, createdAt: date(c.createdAt) ?? .now)
                ctx.insert(fresh); return fresh
            }()
            row.text = c.text; row.status = c.status; row.suggestionState = c.suggestionState
            row.suggestionJSON = try c.suggestion.map { try JSONEncoder().encode($0) }
        }
        if let on = page.settings?.coachReadsJournal { UserDefaults.standard.set(on, forKey: "coachReadsJournal") }
        if let share = page.settings?.share { UserDefaults.standard.set(share, forKey: "communityShare") }
        for c in page.completions where try completion(c.taskId, c.localDate, ctx) == nil {
            ctx.insert(CompletionEntity(taskId: c.taskId, localDate: c.localDate, completedAt: date(c.completedAt) ?? .now))
        }
    }

    private static func goal(_ id: UUID, _ ctx: ModelContext) throws -> GoalEntity? {
        try ctx.fetch(FetchDescriptor<GoalEntity>(predicate: #Predicate { $0.id == id })).first
    }
    private static func completion(_ taskId: UUID, _ localDate: String, _ ctx: ModelContext) throws -> CompletionEntity? {
        let key = CompletionEntity.key(taskId, localDate)
        return try ctx.fetch(FetchDescriptor<CompletionEntity>(predicate: #Predicate { $0.key == key })).first
    }
}

