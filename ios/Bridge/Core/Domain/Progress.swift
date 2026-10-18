import Foundation

/// Calendar dates as "yyyy-MM-dd" in the user's local calendar. All goal maths works on these.
enum Day {
    static let calendar: Calendar = { var c = Calendar(identifier: .gregorian); c.timeZone = .current; return c }()
    static func parse(_ s: String) -> Date? {
        let p = s.split(separator: "-").compactMap { Int($0) }
        guard p.count == 3 else { return nil }
        return calendar.date(from: DateComponents(year: p[0], month: p[1], day: p[2]))
    }
    static func string(_ d: Date) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: d)
        return String(format: "%04d-%02d-%02d", c.year!, c.month!, c.day!)
    }
    static func add(_ days: Int, to s: String) -> String { string(calendar.date(byAdding: .day, value: days, to: parse(s)!)!) }
    static func addMonths(_ months: Int, to s: String) -> String { string(calendar.date(byAdding: .month, value: months, to: parse(s)!)!) }
    static func between(_ a: String, _ b: String) -> Int { calendar.dateComponents([.day], from: parse(a)!, to: parse(b)!).day ?? 0 }
    static var today: String { LocalDay.today() }
}

/// Where a goal stands today. Pure, so it is unit-tested and shared by every screen.
struct GoalProgress: Equatable, Sendable {
    var day: Int
    var total: Int
    var doneToday: Int
    var tasksToday: Int
    var fraction: Double
    var streak: Int
    /// Percentage points against the pace target, or nil before there is any history.
    var drift: Int?
    /// Today is after the deadline: the recap replaces the plan header.
    var overdue = false
    /// Finished days in a row with nothing done, up to yesterday. 3+ shows the gentle re-entry.
    var awayDays = 0
    /// Days with at least one completed task, since the start.
    var activeDays = 0

    var daysLeft: Int { overdue ? 0 : max(0, total - day + 1) }
    var away: Bool { awayDays >= Progress.awayThreshold && doneToday == 0 }
    var todayFraction: Double { tasksToday == 0 ? 0 : Double(doneToday) / Double(tasksToday) }
    var allDone: Bool { tasksToday > 0 && doneToday == tasksToday }
}

enum Progress {
    /// The plan is "on pace" when about 4 in 5 planned tasks get done: consistency, not perfection.
    static let paceTarget = 0.8
    /// Missed this many days in a row: welcome back, no catching up.
    static let awayThreshold = 3

    /// - Parameters:
    ///   - taskCreated: local date each live task first existed (from its UUIDv7 id).
    ///   - completions: local dates with a completion, per task id.
    static func of(start: String, deadline: String, today: String, taskCreated: [UUID: String], completions: [UUID: Set<String>]) -> GoalProgress {
        let day = max(1, Day.between(start, today) + 1)
        let total = max(day, Day.between(start, deadline) + 1)
        let done = taskCreated.keys.filter { completions[$0]?.contains(today) == true }.count
        let share = taskCreated.isEmpty ? 0 : Double(done) / Double(taskCreated.count)
        let days = Set(completions.values.flatMap { $0 })
        let last = days.filter { $0 < today }.max()
        return GoalProgress(day: day, total: total, doneToday: done, tasksToday: taskCreated.count,
                            fraction: min(1, max(0, (Double(day - 1) + share) / Double(total))),
                            streak: streak(Set(completions.values.flatMap { $0 }), today: today),
                            drift: drift(start: start, today: today, taskCreated: taskCreated, completions: completions),
                            overdue: today > deadline,
                            awayDays: max(0, Day.between(last ?? Day.add(-1, to: start), today) - 1),
                            activeDays: days.filter { $0 <= today }.count)
    }

    /// Consecutive days with at least one completed task, ending today (or yesterday while today is still open).
    static func streak(_ days: Set<String>, today: String) -> Int {
        var d = days.contains(today) ? today : Day.add(-1, to: today)
        var n = 0
        while days.contains(d) { n += 1; d = Day.add(-1, to: d) }
        return n
    }

    /// Last 7 finished days: share of planned tasks done, against `paceTarget`. Today is excluded so mornings don't read as "behind".
    static func drift(start: String, today: String, taskCreated: [UUID: String], completions: [UUID: Set<String>]) -> Int? {
        var planned = 0, done = 0
        for i in 1...7 {
            let d = Day.add(-i, to: today)
            if d < start { break }
            for (task, created) in taskCreated where created <= d {
                planned += 1
                if completions[task]?.contains(d) == true { done += 1 }
            }
        }
        guard planned > 0 else { return nil }
        return Int(((Double(done) / Double(planned) - paceTarget) * 100).rounded())
    }

    /// UUIDv7 ids carry their creation time; tasks never count as missed before they existed.
    static func createdDay(_ id: UUID, start: String) -> String {
        let b = id.uuid
        let ms = [b.0, b.1, b.2, b.3, b.4, b.5].reduce(UInt64(0)) { $0 << 8 | UInt64($1) }
        let d = LocalDay.today(now: Date(timeIntervalSince1970: Double(ms) / 1000))
        return d < start ? start : d
    }
}

/// Target dates: the user picks them, the app only checks they are sensible.
enum Deadlines {
    static let minDaysNew = 7       // onboarding and review: at least a week out
    static let minDaysEdit = 1      // editing an existing goal: any day after today
    static let maxDays = 1826       // about 5 years

    enum Problem: Equatable { case missing, tooSoonWeek, tooSoonDay, tooFar }

    static func check(_ deadline: String?, today: String, minDays: Int) -> Problem? {
        guard let deadline else { return .missing }
        let n = Day.between(today, deadline)
        if n < minDays { return minDays > 1 ? .tooSoonWeek : .tooSoonDay }
        if n > maxDays { return .tooFar }
        return nil
    }

    static func message(_ p: Problem) -> String {
        switch p {
        case .missing: String(localized: "Pick a target date.")
        case .tooSoonWeek: String(localized: "Pick a date at least a week from today.")
        case .tooSoonDay: String(localized: "Pick a date after today.")
        case .tooFar: String(localized: "Pick a date within 5 years.")
        }
    }
}

/// Hints shown under the goal field in onboarding step 1 (same rules as the prototype).
enum GoalText {
    static let minLength = 12
    static let maxLength = 200
    private static let dateWords = try! NSRegularExpression(pattern: #"\b(by|before|until)\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec|next|the end|end of|spring|summer|autumn|fall|winter|\d)|\b(in|within)\s+\d+\s*(day|week|month|year)s?"#, options: .caseInsensitive)
    private static let timing = try! NSRegularExpression(pattern: #"\b(by|before|until|in)\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec|next|the end|\d)"#, options: .caseInsensitive)
    private static func matches(_ r: NSRegularExpression, _ s: String) -> Bool { r.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) != nil }

    static func hasDate(_ text: String) -> Bool { matches(dateWords, text) }
    /// A goal with no number can't be measured ("get fit"), so nudge for one.
    static func needsNumber(_ text: String) -> Bool {
        text.trimmingCharacters(in: .whitespaces).count >= minLength && !text.contains(where: \.isNumber) && !matches(timing, text)
    }
    /// Short name for the goal card when the AI didn't provide one: whole words, 24 characters at most.
    static func autoLabel(_ goal: String) -> String {
        if goal.range(of: "half marathon", options: .caseInsensitive) != nil { return "Half marathon" }
        if goal.range(of: "marathon", options: .caseInsensitive) != nil { return "Marathon" }
        let cleaned = goal.filter { $0.isLetter || $0.isNumber || $0.isWhitespace || $0 == "'" || $0 == "-" }
        var words = cleaned.split(whereSeparator: \.isWhitespace).prefix(3).map(String.init)
        while words.count > 1 && words.joined(separator: " ").count > 24 { words.removeLast() }
        let s = String(words.joined(separator: " ").prefix(24))
        return s.prefix(1).uppercased() + s.dropFirst()
    }
}

enum TaskTime {
    /// "7:00 AM", "7am", "19:30", "07:00" → (hour, minute); "After run", "Evening" → nil (no reminder).
    static func parse(_ text: String?) -> (hour: Int, minute: Int)? {
        guard let t = text?.trimmingCharacters(in: .whitespaces).lowercased(), !t.isEmpty else { return nil }
        if let m = t.wholeMatch(of: /(\d{1,2}):(\d{2})/), let h = Int(m.1), let mi = Int(m.2) { return (0...23).contains(h) && (0...59).contains(mi) ? (h, mi) : nil }
        if let m = t.wholeMatch(of: /(\d{1,2})(?::(\d{2}))?\s*([ap])\.?\s*m\.?/), var h = Int(m.1) {
            let mi = m.2.flatMap { Int($0) } ?? 0
            guard (1...12).contains(h), (0...59).contains(mi) else { return nil }
            if m.3 == "p" && h != 12 { h += 12 }
            if m.3 == "a" && h == 12 { h = 0 }
            return (h, mi)
        }
        return nil
    }
}
