import SwiftUI

/// Loading, a result, or why there is none. Students only load online (the lead's view of other people isn't cached).
struct Loadable<T: Equatable>: Equatable {
    var data: T?
    var loading = true
    var offline = false
    var error = false
}

// MARK: - The class: everyone on the plan, those who need attention first, and each task across the class

struct StudentsScreen: View {
    let allianceId: String
    let code: String
    @Environment(SocialStore.self) private var social
    @Environment(AppRouter.self) private var router
    @Environment(\.dismiss) private var dismiss
    @State private var state = Loadable<StudentsDTO>()

    var body: some View {
        StudentsContent(state: state, code: code, onBack: { dismiss() }, onRetry: { Task { await load() } }, onOpen: { s in
            guard let a = UUID(uuidString: allianceId), let u = UUID(uuidString: s.userId) else { return }
            router.today.append(Route.student(allianceId: a, userId: u, name: s.name, plan: state.data?.name ?? ""))
        })
        .toolbar(.hidden, for: .navigationBar)
        // Also on coming back from a student: their nudge or new ticks show up here.
        .task { await load() }
    }

    private func load() async {
        state.loading = true; state.error = false; state.offline = false
        do { state = Loadable(data: try await social.students(allianceId), loading: false) }
        catch { state.loading = false; state.offline = (error as? APIError) == .offline; state.error = !state.offline }
    }
}

struct StudentsContent: View {
    let state: Loadable<StudentsDTO>
    let code: String
    var onBack: () -> Void = {}
    var onRetry: () -> Void = {}
    var onOpen: (StudentDTO) -> Void = { _ in }
    var today: Date = .now

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                BackLink(title: state.data?.name ?? "", action: onBack)
                VStack(alignment: .leading, spacing: 4) {
                    Text("Students").font(BFont.display(25)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                    if let d = state.data { Text("\(d.sharing) of \(d.members) share their progress · last 7 days").font(BFont.body(13)).foregroundStyle(Theme.mut) }
                }
                if let d = state.data {
                    if d.students.isEmpty {
                        VStack(spacing: 8) {
                            Text("No students yet").font(BFont.display(16)).foregroundStyle(Theme.ink)
                            Text("Share the invite code \(code). Students who join show up here.").font(BFont.body(13)).foregroundStyle(Theme.mut).multilineTextAlignment(.center)
                        }
                        .padding(.vertical, 28).padding(.horizontal, 20).frame(maxWidth: .infinity)
                        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Theme.line, lineWidth: 1))
                    } else {
                        classWeek(d)
                        let attention = d.students.filter { $0.progress?.attention != nil }
                        let rest = d.students.filter { $0.progress?.attention == nil }
                        if !attention.isEmpty { section("Needs attention") { ForEach(attention) { row($0) } } }
                        if !rest.isEmpty { section("Everyone") { ForEach(rest) { row($0) } } }
                        if !d.tasks.isEmpty { section("By task") { ForEach(d.tasks) { taskRow($0) } } }
                    }
                } else if state.loading {
                    ForEach(0..<4, id: \.self) { _ in RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Theme.s1).frame(height: 72) }.accessibilityHidden(true)
                } else {
                    Problem(text: state.offline ? String(localized: "You're offline. Students load when you're back online.") : String(localized: "Couldn't load your students."), onRetry: onRetry)
                }
            }
            .padding(.horizontal, Theme.Space.screen).padding(.top, 6).padding(.bottom, 40)
        }
        .background(Theme.bg)
    }

    private func section<C: View>(_ title: LocalizedStringKey, @ViewBuilder _ content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).bridgeLabel(11).accessibilityAddTraits(.isHeader)
            content()
        }
    }

    private func classWeek(_ d: StudentsDTO) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("CLASS THIS WEEK").bridgeLabel(10)
            HStack(alignment: .lastTextBaseline, spacing: 10) {
                Text("\(Int((d.week.rate * 100).rounded()))%").font(BFont.display(32)).foregroundStyle(Theme.ink)
                Text("\(d.week.done) of \(d.week.due) tasks done").font(BFont.body(13)).foregroundStyle(Theme.mut)
            }
            Bar(fraction: d.week.rate, color: Theme.acc, height: 6)
        }
        .padding(18).frame(maxWidth: .infinity, alignment: .leading).card(20).accessibilityElement(children: .combine)
    }

    private func row(_ s: StudentDTO) -> some View {
        let status = statusLine(s)
        let pct = s.progress.map { Int(($0.week.rate * 100).rounded()) }
        let content = HStack(spacing: 12) {
            Avatar(name: s.name, size: 38, ink: Theme.ink2)
            VStack(alignment: .leading, spacing: 3) {
                Text(s.name).font(BFont.body(15, .semibold)).foregroundStyle(Theme.ink).lineLimit(1)
                Text(status).font(BFont.body(12)).foregroundStyle(s.progress?.attention != nil ? Theme.bad : Theme.mut)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if let p = s.progress, let pct {
                VStack(alignment: .trailing, spacing: 5) {
                    Text("\(pct)%").font(BFont.label(13)).foregroundStyle(Theme.ink)
                    Bar(fraction: p.week.rate, color: Theme.ink2, height: 4)
                }
                .frame(width: 64)
                Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.mut2)
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 10).frame(minHeight: 64).card(16)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(pct.map { String(localized: "\(s.name), \(status), \($0)% this week") } ?? "\(s.name), \(status)"))
        return Group {
            if s.progress != nil { Button { onOpen(s) } label: { content }.buttonStyle(.plain).accessibilityAddTraits(.isButton) } else { content }
        }
    }

    private func statusLine(_ s: StudentDTO) -> String {
        guard let p = s.progress else { return String(localized: "Not sharing progress") }
        if s.finished { return String(localized: "Finished the plan") }
        switch p.attention {
        case "away": return awayText(p.lastActive, today)
        case "low_feel": return String(localized: "Says it feels hard")
        case "behind": return String(localized: "Under half done this week")
        default: return String(localized: "\(p.today.done) of \(p.today.total) done today")
        }
    }

    private func taskRow(_ t: ClassTaskDTO) -> some View {
        let rate = t.due == 0 ? 0 : Double(t.done) / Double(t.due)
        return VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text(t.title).font(BFont.body(14, .medium)).foregroundStyle(Theme.ink).frame(maxWidth: .infinity, alignment: .leading)
                Text("\(Int((rate * 100).rounded()))% done").font(BFont.label(12)).foregroundStyle(Theme.ink2)
            }
            Bar(fraction: rate, color: Theme.ink2, height: 4)
            if let f = t.feel { Text("Feels \(feelWord(Int(f.rounded())).lowercased())").font(BFont.body(12)).foregroundStyle(f <= 2 ? Theme.bad : Theme.mut) }
        }
        .padding(14).frame(maxWidth: .infinity, alignment: .leading).card(16).accessibilityElement(children: .combine)
    }
}

// MARK: - One student: their last 7 days task by task, check-ins, and a nudge

struct StudentScreen: View {
    let allianceId: String
    let userId: String
    let name: String
    let plan: String
    @Environment(SocialStore.self) private var social
    @Environment(Toaster.self) private var toaster
    @Environment(\.dismiss) private var dismiss
    @State private var state = Loadable<StudentDetailDTO>()
    @State private var stopped = false
    @State private var nudging = false

    var body: some View {
        StudentContent(state: state, name: name, plan: plan, stopped: stopped, nudging: nudging, onBack: { dismiss() }, onRetry: { Task { await load() } }, onNudge: nudge)
            .toolbar(.hidden, for: .navigationBar)
            .task { await load() }
    }

    private func load() async {
        state.loading = true; state.error = false; state.offline = false
        do { state = Loadable(data: try await social.student(allianceId, userId: userId), loading: false) }
        catch APIError.server(_, "not_sharing", _, _) { stopped = true; state.loading = false }
        catch { state.loading = false; state.offline = (error as? APIError) == .offline; state.error = !state.offline }
    }

    private func nudge() {
        guard !nudging, state.data != nil else { return }
        nudging = true
        Task {
            defer { nudging = false }
            do {
                try await social.nudgeStudent(allianceId, userId: userId)
                state.data?.nudgedToday = true
                toaster.show(String(localized: "\(name) got your nudge"), .ok)
            } catch APIError.server(_, "already_nudged", _, _) { state.data?.nudgedToday = true }
            catch { toaster.show((error as? APIError) == .offline ? String(localized: "You're offline. Try again when you reconnect.") : String(localized: "Something went wrong. Try again."), .warn) }
        }
    }
}

struct StudentContent: View {
    let state: Loadable<StudentDetailDTO>
    let name: String
    let plan: String
    var stopped = false
    var nudging = false
    var onBack: () -> Void = {}
    var onRetry: () -> Void = {}
    var onNudge: () -> Void = {}
    var today: Date = .now

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                BackLink(title: String(localized: "Students"), action: onBack)
                HStack(spacing: 14) {
                    Avatar(name: name, size: 52)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(name).font(BFont.display(25)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                        if let d = state.data, let joined = isoDay(d.startDate) {
                            Text("Joined \(joined.formatted(Date.FormatStyle(timeZone: TimeZone(identifier: "UTC")!).month(.abbreviated).day()))").font(BFont.body(13)).foregroundStyle(Theme.mut)
                        }
                    }
                }
                if stopped {
                    Problem(text: String(localized: "\(name) isn't sharing their progress now."), onRetry: nil)
                } else if let d = state.data {
                    HStack(spacing: 8) {
                        tile("This week", "\(Int((d.week.rate * 100).rounded()))%")
                        tile("Streak", String(localized: "\(d.streak) days"))
                        tile("Feels", d.feel.map { feelWord(Int($0.rounded())) } ?? "–")
                    }
                    if let a = d.attention {
                        Text(a == "away" ? awayText(d.lastActive, today) : a == "low_feel" ? String(localized: "Says it feels hard") : String(localized: "Under half done this week"))
                            .font(BFont.body(13, .medium)).foregroundStyle(Theme.bad).padding(12).frame(maxWidth: .infinity, alignment: .leading)
                            .background(Theme.badTint, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }
                    Text("LAST 7 DAYS").bridgeLabel(11).accessibilityAddTraits(.isHeader)
                    grid(d)
                    Text("CHECK-INS").bridgeLabel(11).accessibilityAddTraits(.isHeader)
                    let notes = d.tasks.flatMap { t in t.days.filter { $0.feel != nil || $0.amount != nil || $0.note != nil }.map { (t, $0) } }.sorted { $0.1.date > $1.1.date }
                    if notes.isEmpty { Text("No check-ins this week.").font(BFont.body(13)).foregroundStyle(Theme.mut) }
                    ForEach(Array(notes.enumerated()), id: \.offset) { _, n in checkinCard(n.0, n.1) }
                    VStack(alignment: .leading, spacing: 8) {
                        Button(action: onNudge) {
                            if nudging { ProgressView().tint(Theme.onAcc) } else { Text(d.nudgedToday ? "Nudged today" : "Send a nudge") }
                        }
                        .buttonStyle(PrimaryButtonStyle()).disabled(d.nudgedToday || d.finished || nudging)
                        Text("They get a notification: keep going on \(plan). It never names a task.").font(BFont.body(12)).foregroundStyle(Theme.mut)
                    }
                } else if state.loading {
                    ForEach(0..<3, id: \.self) { _ in RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Theme.s1).frame(height: 96) }.accessibilityHidden(true)
                } else {
                    Problem(text: state.offline ? String(localized: "You're offline. Students load when you're back online.") : String(localized: "Couldn't load your students."), onRetry: onRetry)
                }
            }
            .padding(.horizontal, Theme.Space.screen).padding(.top, 6).padding(.bottom, 40)
        }
        .background(Theme.bg)
    }

    private func tile(_ label: LocalizedStringKey, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).bridgeLabel(9.5).lineLimit(1)
            Text(value).font(BFont.display(18)).foregroundStyle(Theme.ink)
        }
        .padding(12).frame(maxWidth: .infinity, alignment: .leading).card(16).accessibilityElement(children: .combine)
    }

    /// One row per task, one dot per day: done, missed, not yet (today) or before they joined.
    private func grid(_ d: StudentDetailDTO) -> some View {
        let days = d.days.compactMap(isoDay)
        let short = { (x: Date) in x.formatted(Date.FormatStyle(timeZone: TimeZone(identifier: "UTC")!).weekday(.abbreviated)) }
        return VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 0) {
                Spacer(minLength: 0)
                ForEach(Array(days.enumerated()), id: \.offset) { _, x in Text(String(short(x).prefix(1))).font(BFont.label(10)).foregroundStyle(Theme.mut).frame(width: 26) }
            }
            .accessibilityHidden(true)
            ForEach(Array(d.tasks.enumerated()), id: \.offset) { _, t in
                HStack(spacing: 0) {
                    Text(t.title).font(BFont.body(13, .medium)).foregroundStyle(Theme.ink).lineLimit(2).frame(maxWidth: .infinity, alignment: .leading).padding(.trailing, 8)
                    ForEach(Array(t.days.enumerated()), id: \.offset) { _, x in dot(x.state).frame(width: 26) }
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Text("\(t.title). " + zip(t.days, days).map { x, date in spoken(x.state, short(date)) }.joined(separator: ", ")))
            }
        }
        .padding(14).card(18)
    }

    private func spoken(_ state: String, _ day: String) -> String {
        switch state {
        case "done": String(localized: "\(day) done")
        case "missed": String(localized: "\(day) missed")
        case "open": String(localized: "\(day) not yet")
        default: String(localized: "\(day) before joining")
        }
    }

    @ViewBuilder private func dot(_ state: String) -> some View {
        switch state {
        case "done": Image(systemName: "checkmark").font(.system(size: 9, weight: .bold)).foregroundStyle(Theme.onOk).frame(width: 18, height: 18).background(Theme.okFill, in: Circle())
        case "missed": Circle().strokeBorder(Theme.bad, lineWidth: 1.5).frame(width: 18, height: 18)
        case "open": Circle().strokeBorder(Theme.line2, style: StrokeStyle(lineWidth: 1, dash: [3, 3])).frame(width: 18, height: 18)
        default: Circle().fill(Theme.line2).frame(width: 5, height: 5)
        }
    }

    private func checkinCard(_ t: StudentTaskDTO, _ x: StudentDayDTO) -> some View {
        let day = isoDay(x.date).map { $0.formatted(Date.FormatStyle(timeZone: TimeZone(identifier: "UTC")!).weekday(.abbreviated)) }
        let meta = [day, x.feel.map(feelWord), x.amount.map { Measure.amount($0, t.measure) }].compactMap { $0 }.joined(separator: " · ")
        return VStack(alignment: .leading, spacing: 5) {
            Text(t.title).font(BFont.body(14, .semibold)).foregroundStyle(Theme.ink)
            Text(meta).font(BFont.body(12)).foregroundStyle((x.feel ?? 3) <= 2 ? Theme.bad : Theme.mut)
            if let n = x.note { Text(n).font(BFont.body(14)).foregroundStyle(Theme.ink2) }
        }
        .padding(14).frame(maxWidth: .infinity, alignment: .leading).card(16).accessibilityElement(children: .combine)
    }
}

// MARK: - Shared bits

private struct BackLink: View {
    let title: String
    var action: () -> Void
    var body: some View {
        Button(action: action) { Label(title, systemImage: "chevron.left").font(BFont.body(15, .medium)).foregroundStyle(Theme.ink).frame(minHeight: 44) }
            .padding(.leading, -2).accessibilityLabel(Text("Back"))
    }
}

private struct Problem: View {
    let text: String
    var onRetry: (() -> Void)?
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(text).font(BFont.body(14, .medium)).foregroundStyle(Theme.ink)
            if let onRetry { Button("Try again", action: onRetry).buttonStyle(SecondaryButtonStyle()) }
        }
        .padding(20).frame(maxWidth: .infinity, alignment: .leading).card(18)
    }
}

/// "2026-10-13" at midnight UTC (formatted in UTC, so the day never shifts).
private func isoDay(_ s: String) -> Date? {
    let f = DateFormatter(); f.calendar = Calendar(identifier: .gregorian); f.dateFormat = "yyyy-MM-dd"; f.timeZone = TimeZone(identifier: "UTC")
    return f.date(from: s)
}

/// "Nothing ticked in 4 days", or "Hasn't ticked anything yet".
private func awayText(_ lastActive: String?, _ today: Date) -> String {
    guard let last = lastActive.flatMap(isoDay), let now = isoDay(LocalDay.today(now: today)) else { return String(localized: "Hasn't ticked anything yet") }
    let n = max(1, Int((now.timeIntervalSince(last) / 86_400).rounded()))
    return String(localized: "Nothing ticked in \(n) days")
}
