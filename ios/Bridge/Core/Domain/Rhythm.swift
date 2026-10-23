import Foundation
import SwiftData

/// One time the app came to the front, and whether the phone was on Wi-Fi (nil: offline or unknown).
struct AppOpen: Equatable, Sendable { let at: Date; let wifi: Bool? }

/// When someone actually uses the app, learned from the last 28 days on the device (with their permission).
/// Hours are 0...23, except `finishedBy`, which can run past midnight (24, 25, 26) because the day closes at 03:00.
/// Same rules as Android `domain/Rhythm.kt`.
struct Rhythm: Equatable, Sendable {
    /// Days with an open or a completed task in the window.
    let days: Int
    /// The hour the app is most often opened.
    let openHour: Int
    /// The hour tasks most often get done; nil before any are.
    let doneHour: Int?
    /// By this hour most days' last task is done (80% of days); nil before any are.
    let finishedBy: Int?
    /// Share of opens on Wi-Fi (a rough "at home or work" signal; never the location).
    let wifiShare: Double?

    /// Daily reminder: an hour before tasks usually get done (or before the usual open), kept between 06:00 and 21:00.
    var dailyReminder: DateComponents { DateComponents(hour: min(21, max(6, (doneHour ?? openHour) - 1)), minute: 0) }
    /// Streak at risk: by now most days are finished, so nothing done yet deserves a nudge. Kept between 17:00 and 22:00.
    var streakRisk: DateComponents { DateComponents(hour: min(22, max(17, finishedBy ?? ((doneHour ?? openHour) + 2))), minute: 0) }
}

enum RhythmCalc {
    static let windowDays = 28
    /// Fewer active days than this says too little about a routine.
    static let minDays = 5

    /// `completions`: when tasks were ticked, with the local day each counted for (a tick at 01:30 belongs to the day before
    /// and counts as hour 25 for `finishedBy`).
    static func of(opens: [AppOpen], completions: [(day: String, at: Date)], today: String, calendar: Calendar = .current) -> Rhythm? {
        let from = Day.add(-(windowDays - 1), to: today)
        let dayString = { (d: Date) in Day.string(d) }
        let o = opens.filter { dayString($0.at) >= from }
        let c = completions.filter { $0.day >= from && $0.day <= today }
        let openDays = o.map { open -> String in
            let h = calendar.component(.hour, from: open.at)
            return h < 3 ? Day.add(-1, to: dayString(open.at)) : dayString(open.at)
        }
        let days = Set(openDays + c.map(\.day)).count
        guard days >= minDays, !o.isEmpty else { return nil }
        func hourInDay(_ day: String, _ at: Date) -> Int { calendar.component(.hour, from: at) + 24 * Day.between(day, dayString(at)) }
        let lastPerDay = Dictionary(grouping: c, by: \.day).values.map { list in list.map { hourInDay($0.day, $0.at) }.max()! }.sorted()
        let finishedBy = lastPerDay.isEmpty ? nil : lastPerDay[min(lastPerDay.count - 1, max(0, Int((Double(lastPerDay.count) * 0.8).rounded(.up)) - 1))] + 1
        let known = o.compactMap(\.wifi)
        return Rhythm(days: days, openHour: peak(o.map { calendar.component(.hour, from: $0.at) }),
                      doneHour: c.isEmpty ? nil : peak(c.map { calendar.component(.hour, from: $0.at) }),
                      finishedBy: finishedBy, wifiShare: known.isEmpty ? nil : Double(known.filter { $0 }.count) / Double(known.count))
    }

    /// The busiest hour, smoothed with its neighbours (18:58 and 19:02 are the same habit); ties go to the earlier hour.
    static func peak(_ hours: [Int]) -> Int {
        var n = [Int](repeating: 0, count: 24)
        for h in hours { n[((h % 24) + 24) % 24] += 1 }
        let score = { (h: Int) in n[(h + 23) % 24] + 2 * n[h] + n[(h + 1) % 24] }
        return (0..<24).reduce(0) { best, h in score(h) > score(best) ? h : best }
    }
}

/// "Let Arc learn my routine" (off until the user turns it on): when the app is opened and whether the phone is on Wi-Fi,
/// kept on this device for 28 days. With task completions (already local) it times the reminders and is summarised for
/// the coach. Nothing here is sent anywhere raw, and turning it off deletes it.
@MainActor
enum RhythmStore {
    private static let onKey = "rhythmOn", opensKey = "rhythmOpens"
    static var enabled: Bool { UserDefaults.standard.bool(forKey: onKey) }

    static func setEnabled(_ on: Bool) {
        UserDefaults.standard.set(on, forKey: onKey)
        if !on { UserDefaults.standard.removeObject(forKey: opensKey) }
    }

    /// The app came to the front. Opens within 10 minutes of the last one are the same visit.
    static func recordOpen(wifi: Bool?, now: Date = .now) {
        guard enabled else { return }
        let cutoff = now.addingTimeInterval(-Double(RhythmCalc.windowDays + 1) * 86_400)
        var kept = opens().filter { $0.at >= cutoff }
        if let last = kept.last, now.timeIntervalSince(last.at) < 600 { return }
        kept.append(AppOpen(at: now, wifi: wifi))
        let raw = kept.suffix(600).map { "\(Int($0.at.timeIntervalSince1970)):\($0.wifi.map { $0 ? "w" : "c" } ?? "n")" }
        UserDefaults.standard.set(raw.joined(separator: ","), forKey: opensKey)
    }

    private static func opens() -> [AppOpen] {
        (UserDefaults.standard.string(forKey: opensKey) ?? "").split(separator: ",").compactMap { e in
            let p = e.split(separator: ":")
            guard p.count == 2, let t = Double(p[0]) else { return nil }
            return AppOpen(at: Date(timeIntervalSince1970: t), wifi: p[1] == "w" ? true : p[1] == "c" ? false : nil)
        }
    }

    /// The learned rhythm, or nil when learning is off or there isn't enough to go on yet.
    static func current() -> Rhythm? {
        guard enabled else { return nil }
        let done = ((try? Persistence.container.mainContext.fetch(FetchDescriptor<CompletionEntity>())) ?? []).map { (day: $0.localDate, at: $0.completedAt) }
        return RhythmCalc.of(opens: opens(), completions: done, today: LocalDay.today())
    }

    /// What actually fires: the learned times when there is a routine to learn from; turning a reminder off still wins.
    static func effective(_ s: ReminderSettings) -> ReminderSettings {
        guard let r = current() else { return s }
        return ReminderSettings(daily: s.daily == nil ? nil : r.dailyReminder, streakRisk: s.streakRisk == nil ? nil : r.streakRisk, learned: true)
    }
}
