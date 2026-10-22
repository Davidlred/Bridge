import Foundation
import SwiftUI

enum OnboardingStep: String, Codable { case goal, routine, photo, generating, review }
enum PlanState: String, Codable { case pending, running, done, failed, clarify, quota }
enum PhotoState: String, Codable { case none, uploading, done, failed }

struct DraftTask: Codable, Identifiable, Equatable {
    var id = UUID()
    var title: String
    var priority = "Medium"
    var timeOfDay: String?
    var minutes: Int?
    var rationale: String?
    var ai = false
    var resources: [LinkDTO]?
}

/// A one-time first-week step from the plan.
struct DraftStep: Codable, Identifiable, Equatable {
    var id = UUID()
    var day: Int
    var title: String
    var minutes: Int?
    var rationale: String?
    var resources: [LinkDTO]?
}

/// Everything typed during onboarding. Saved on every change so a killed app resumes at the same step with the same inputs.
struct OnboardingDraft: Codable, Equatable {
    var goalId = UUIDv7.next()
    var adding = false
    var step = OnboardingStep.goal
    var goal = ""
    var deadline: String?
    var routine = ""
    var routineFromSetup = false
    var photo = PhotoState.none
    var photoUrl: String?
    var plan = PlanState.pending
    var clarification: String?
    var label = ""
    var tasks: [DraftTask] = []
    /// Optional so drafts saved by an older version still load.
    var steps: [DraftStep]?
    var feasibility: Feasibility?
    var category: String?
    var genStartedAt: Date?
    /// The image request itself failed (offline, daily limit), so no image rows exist to report it.
    var imagesError = false
}

@MainActor @Observable
final class OnboardingModel {
    var draft: OnboardingDraft { didSet { save() } }
    private(set) var images: [String: GoalImage] = [:]
    var labelError: String?
    @ObservationIgnored private let store: GoalStore
    @ObservationIgnored private weak var session: SessionStore?
    @ObservationIgnored private weak var toaster: Toaster?
    @ObservationIgnored private var planTask: Task<Void, Never>?
    @ObservationIgnored private var imageTask: Task<Void, Never>?
    private static let key = "onboardingDraft"

    init(adding: Bool, store: GoalStore, session: SessionStore, toaster: Toaster) {
        self.store = store; self.session = session; self.toaster = toaster
        if let data = UserDefaults.standard.data(forKey: Self.key), let saved = try? JSONDecoder().decode(OnboardingDraft.self, from: data), saved.adding == adding {
            draft = saved
        } else {
            var d = OnboardingDraft(adding: adding)
            if adding, let r = store.lastRoutine, !r.isEmpty { d.routine = r; d.routineFromSetup = true }
            if let url = session.user?.photoUrl { d.photo = .done; d.photoUrl = url }
            draft = d
        }
        images = store.images(for: draft.goalId)
        // A generation that was running when the app was killed picks up again.
        if draft.step == .generating { generate() }
    }

    private func save() { UserDefaults.standard.set(try? JSONEncoder().encode(draft), forKey: Self.key) }
    func discard() { planTask?.cancel(); imageTask?.cancel(); UserDefaults.standard.removeObject(forKey: Self.key) }

    var today: String { LocalDay.today() }
    var otherGoals: Int { store.goals.count }
    var otherTasks: Int { store.goals.reduce(0) { $0 + $1.tasks.count } }
    func deadlineProblem(min: Int = Deadlines.minDaysNew) -> Deadlines.Problem? { Deadlines.check(draft.deadline, today: today, minDays: min) }
    var goalReady: Bool { draft.goal.trimmingCharacters(in: .whitespaces).count >= GoalText.minLength && deadlineProblem() == nil }
    var routineReady: Bool { draft.routine.trimmingCharacters(in: .whitespaces).count >= 20 }
    var imagesRunning: Bool { ["future", "reality"].contains { images[$0]?.status == "running" || (!draft.imagesError && draft.plan != .pending && images[$0] == nil) } }
    var imagesFailed: Bool { draft.imagesError || images.values.contains { $0.status == "failed" } }
    var canStart: Bool { draft.tasks.contains { !$0.title.trimmingCharacters(in: .whitespaces).isEmpty } && deadlineProblem() == nil }
    func imageStatus(_ kind: String) -> String {
        if images[kind]?.status == "done" { return "done" }
        if images[kind]?.status == "failed" || (draft.imagesError && images[kind] == nil) { return "failed" }
        if draft.plan == .pending && images[kind] == nil { return "pending" }
        return "running"
    }

    // MARK: Steps
    func toRoutine() { if goalReady { draft.step = .routine; Analytics.track(.onboardingStep, ["step": "routine"]) } }
    func toPhoto() { if routineReady { draft.step = .photo; Analytics.track(.onboardingStep, ["step": "photo"]) } }
    func back() {
        switch draft.step {
        case .routine: draft.step = .goal
        case .photo: draft.step = .routine
        case .generating: cancelGeneration()
        case .review: draft.step = .goal
        case .goal: break
        }
    }

    /// The view shows the consent sheet before calling this.
    func uploadPhoto(_ data: Data) {
        guard let jpeg = PhotoPrep.jpeg(data) else { draft.photo = .failed; return }
        draft.photo = .uploading
        Task {
            do {
                draft.photoUrl = try await APIClient.shared.uploadPhoto(jpeg: jpeg)
                draft.photo = .done
                await session?.refreshUser()
            } catch { draft.photo = .failed }
        }
    }
    func removePhoto() {
        Task {
            try? await APIClient.shared.deletePhoto()
            await session?.refreshUser()
            draft.photo = .none; draft.photoUrl = nil
        }
    }

    func generate() {
        draft.step = .generating
        if draft.plan != .done { draft.plan = .running }
        draft.genStartedAt = .now
        startPlan(); startImages(["future", "reality"])
    }

    private func startPlan() {
        guard draft.plan != .done, planTask == nil else { return }
        draft.plan = .running; draft.clarification = nil
        let d = draft, others = otherGoals
        planTask = Task {
            defer { planTask = nil }
            do {
                let started = Date.now
                let plan = try await APIClient.shared.plan(goal: d.goal.trimmingCharacters(in: .whitespaces), deadline: d.deadline!, today: LocalDay.today(), routine: d.routine.trimmingCharacters(in: .whitespaces), existingGoals: others)
                Analytics.track(.planGenerated, ["latency_ms": Int(Date.now.timeIntervalSince(started) * 1000), "tasks": plan.tasks.count, "clarify": plan.needsClarification != nil])
                if let q = plan.needsClarification { draft.plan = .clarify; draft.clarification = q; return }
                draft.label = draft.label.isEmpty ? (plan.label.isEmpty ? GoalText.autoLabel(d.goal) : plan.label) : draft.label
                draft.feasibility = plan.feasibility; draft.category = plan.category
                draft.tasks = plan.tasks.map { DraftTask(title: $0.title, priority: $0.priority, timeOfDay: $0.timeOfDay, minutes: $0.minutes, rationale: $0.rationale, ai: true, resources: $0.resources) }
                draft.steps = (plan.steps ?? []).map { DraftStep(day: $0.day, title: $0.title, minutes: $0.minutes, rationale: $0.rationale, resources: $0.resources) }
                draft.plan = .done
                advanceIfReady()
            } catch APIError.server(let status, let code, _, _) where status == 429 || code == "ai_quota" {
                draft.plan = .quota
            } catch is CancellationError {
            } catch {
                draft.plan = .failed
            }
        }
    }

    private func startImages(_ kinds: [String]) {
        guard imageTask == nil else { return }
        draft.imagesError = false
        let d = draft
        imageTask = Task {
            defer { imageTask = nil }
            do {
                store.saveImages(d.goalId, try await APIClient.shared.startImages(goalId: d.goalId, goal: d.goal, routine: d.routine, kinds: kinds))
                images = store.images(for: d.goalId)
                let until = Date.now.addingTimeInterval(180)
                while imagesRunning && Date.now < until && !Task.isCancelled {
                    try await Task.sleep(for: .seconds(2.5))
                    store.saveImages(d.goalId, try await APIClient.shared.images(goalId: d.goalId))
                    images = store.images(for: d.goalId)
                }
            } catch {
                draft.imagesError = true                         // offline or daily limit
            }
            advanceIfReady()
        }
    }

    func retry() {
        if draft.plan == .failed || draft.plan == .quota { startPlan() }
        let failed = ["future", "reality"].filter { images[$0]?.status == "failed" || (draft.imagesError && images[$0] == nil) }
        if !failed.isEmpty { startImages(failed) }
    }

    /// Plan failed: start from three blank rows.
    func writeOwnTasks() {
        planTask?.cancel()
        draft.plan = .done
        if draft.label.isEmpty { draft.label = GoalText.autoLabel(draft.goal) }
        draft.tasks = (0..<3).map { _ in DraftTask(title: "") }
        draft.step = .review
    }

    /// Images are optional: the plan can start without them (they keep generating and appear when ready).
    func continueWithoutImages() { if draft.plan == .done { draft.step = .review } }

    /// Plan ready and both images settled: open the review after a beat, as in the prototype.
    private func advanceIfReady() {
        guard draft.step == .generating, draft.plan == .done, !imagesRunning, !imagesFailed else { return }
        Task { try? await Task.sleep(for: .seconds(1.1)); if draft.step == .generating { draft.step = .review } }
    }

    /// Back from Generating: cancel and return to step 1 with every input kept.
    func cancelGeneration() { planTask?.cancel(); draft.step = .goal; if draft.plan != .done { draft.plan = .pending } }

    // MARK: Review
    func addTask() { draft.tasks.append(DraftTask(title: "")) }
    func deleteTask(_ id: UUID) { draft.tasks.removeAll { $0.id == id } }
    func deleteStep(_ id: UUID) { draft.steps?.removeAll { $0.id == id } }

    func start() -> Bool {
        guard draft.label.trimmingCharacters(in: .whitespaces).count >= 2 else { labelError = String(localized: "Give the goal a short name."); return false }
        guard canStart, let deadline = draft.deadline else { return false }
        let d = draft
        store.createGoal(id: d.goalId, label: d.label, statement: d.goal, deadline: deadline, routine: d.routine.isEmpty ? nil : d.routine, feasibility: d.feasibility, category: d.category,
                         tasks: d.tasks.filter { !$0.title.trimmingCharacters(in: .whitespaces).isEmpty }.map { NewTask(title: $0.title, priority: $0.priority, timeOfDay: $0.timeOfDay, minutes: $0.minutes, rationale: $0.rationale, ai: $0.ai, resources: $0.resources ?? []) },
                         steps: (d.steps ?? []).map { NewStep(day: $0.day, title: $0.title, minutes: $0.minutes, rationale: $0.rationale, resources: $0.resources ?? []) })
        UserDefaults.standard.removeObject(forKey: Self.key)
        if d.adding { toaster?.show(String(localized: "\(d.label.trimmingCharacters(in: .whitespaces)) added. \(otherGoals) goals running."), .ok) }
        else { session?.onboardingDone(); toaster?.show(String(localized: "Day 1 starts now."), .ok) }
        Task { await NotificationScheduler.shared.requestProvisional(); await NotificationScheduler.shared.reschedule() }
        return true
    }
}
