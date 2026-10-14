import Foundation
import SwiftData
@preconcurrency import UserNotifications

/// Reminder times chosen in Profile → Notifications. nil = off.
struct ReminderSettings: Equatable, Sendable {
    var daily: DateComponents? = DateComponents(hour: 8, minute: 0)
    var streakRisk: DateComponents? = DateComponents(hour: 20, minute: 0)

    static func load() -> ReminderSettings {
        let d = UserDefaults.standard
        func read(_ key: String, _ def: Int) -> DateComponents? {
            guard let v = d.object(forKey: key) as? Int else { return DateComponents(hour: def, minute: 0) }
            return v < 0 ? nil : DateComponents(hour: v / 60, minute: v % 60)
        }
        return ReminderSettings(daily: read("reminder.daily", 8), streakRisk: read("reminder.risk", 20))
    }
    func save() {
        let enc = { (c: DateComponents?) in c.map { ($0.hour ?? 0) * 60 + ($0.minute ?? 0) } ?? -1 }
        UserDefaults.standard.set(enc(daily), forKey: "reminder.daily")
        UserDefaults.standard.set(enc(streakRisk), forKey: "reminder.risk")
    }
}

/// Local reminders scheduled on the device, so they work offline. iOS keeps at most 64 pending requests,
/// so only the next 3 days are scheduled; every change to the plan, sync and app launch reschedules.
/// Wall-clock components, so DST shifts don't move them. Streak-at-risk and task reminders for days already done are skipped.
actor NotificationScheduler {
    static let shared = NotificationScheduler()
    private let center = UNUserNotificationCenter.current()
    private let days = 3

    /// iOS: provisional (quiet) authorisation after the first plan; the full prompt comes later (after day 1).
    func requestProvisional() async { _ = try? await center.requestAuthorization(options: [.alert, .sound, .provisional]) }
    func requestFull() async -> Bool { (try? await center.requestAuthorization(options: [.alert, .sound])) ?? false }
    func authorized() async -> Bool {
        let s = await center.notificationSettings().authorizationStatus
        return s == .authorized || s == .provisional || s == .ephemeral
    }

    func reschedule() async {
        let snapshot = await MainActor.run { Self.snapshot() }
        center.removeAllPendingNotificationRequests()
        guard snapshot.hasGoals else { return }
        let settings = ReminderSettings.load()
        var requests: [UNNotificationRequest] = []
        for offset in 0..<days {
            let day = Day.add(offset, to: LocalDay.today())
            guard let date = Day.parse(day) else { continue }
            func at(_ time: DateComponents, id: String, title: String, body: String?) {
                var c = Day.calendar.dateComponents([.year, .month, .day], from: date)
                c.hour = time.hour; c.minute = time.minute
                guard let fire = Day.calendar.date(from: c), fire > .now else { return }
                let content = UNMutableNotificationContent()
                content.title = title
                if let body { content.body = body }
                content.sound = .default
                requests.append(UNNotificationRequest(identifier: "\(id)|\(day)", content: content, trigger: UNCalendarNotificationTrigger(dateMatching: c, repeats: false)))
            }
            if let d = settings.daily { at(d, id: "daily", title: String(localized: "Your plan is ready. Let's make progress today."), body: nil) }
            // Today: skip the nudge if something is already done. Later days are re-evaluated on the next reschedule.
            if let r = settings.streakRisk, !(offset == 0 && snapshot.anyDoneToday) {
                at(r, id: "risk", title: String(localized: "Don't break your streak — one task is better than none."), body: nil)
            }
            for t in snapshot.tasks where !(offset == 0 && t.doneToday) {
                guard let (h, m) = TaskTime.parse(t.time) else { continue }
                at(DateComponents(hour: h, minute: m), id: "task-\(t.id)", title: t.title, body: String(localized: "Planned for \(t.time ?? "")"))
            }
        }
        for r in requests.prefix(60) { try? await center.add(r) }
    }

    private struct Snapshot: Sendable {
        struct T: Sendable { let id: String; let title: String; let time: String?; let doneToday: Bool }
        let hasGoals: Bool; let anyDoneToday: Bool; let tasks: [T]
    }

    @MainActor private static func snapshot() -> Snapshot {
        let ctx = Persistence.container.mainContext
        let today = LocalDay.today()
        let goals = (try? ctx.fetch(FetchDescriptor<GoalEntity>(predicate: #Predicate { $0.status == "active" }))) ?? []
        let done = Set(((try? ctx.fetch(FetchDescriptor<CompletionEntity>(predicate: #Predicate { $0.localDate == today }))) ?? []).map(\.taskId))
        let tasks = goals.flatMap(\.tasks).filter { $0.deletedAt == nil }
        return Snapshot(hasGoals: !goals.isEmpty, anyDoneToday: tasks.contains { done.contains($0.id) },
                        tasks: tasks.map { .init(id: $0.id.uuidString, title: $0.title, time: $0.timeOfDay, doneToday: done.contains($0.id)) })
    }
}
