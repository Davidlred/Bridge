import SwiftUI

enum GoalView: Hashable { case today, reality }

/// Today tab state that isn't stored: which goal is open, which view, the celebration, Reality Check refresh.
@MainActor @Observable
final class TodayModel {
    var openGoalId: UUID?
    var view = GoalView.today
    var celebrate: UUID?
    /// The goal just marked complete, kept for the celebration after it leaves the active list.
    var finished: GoalState?
    var realityBusy = false
    var realityError = false
    /// The goal whose next steps are being planned.
    var stepsBusy: UUID?
    @ObservationIgnored private var noteTask: Task<Void, Never>?
    @ObservationIgnored private var imagesRefreshedAt: [UUID: Date] = [:]

    func open(_ id: UUID?) {
        openGoalId = id; view = .today
        if let id { refreshImages(for: [id]); CoachFocus.set(id, goalIds: store?.goals.map(\.id) ?? []) }
    }

    @ObservationIgnored weak var store: GoalStore?
    @ObservationIgnored weak var social: SocialStore?

    /// Signed image links last an hour, and after a new login or reinstall there are no image rows at all. Renewed for every
    /// active goal when Today is shown or the app comes back, at most every 30 minutes per goal. (Only renewing on "open goal"
    /// missed people with a single goal, who never open one.)
    func refreshImages(for ids: [UUID]) {
        for id in ids where Date.now.timeIntervalSince(imagesRefreshedAt[id] ?? .distantPast) > 30 * 60 {
            imagesRefreshedAt[id] = .now
            Task { await refreshImages(id) }
        }
    }

    func refreshImages(_ id: UUID) async {
        guard let dtos = try? await APIClient.shared.images(goalId: id) else { return }
        store?.saveImages(id, dtos)
    }

    func toggle(_ task: PlanTask, in goal: GoalState) {
        let done = !task.done
        store?.setDone(task.id, done)
        if done { Analytics.track(.taskCompleted) }
        if done { Task { await social?.announce(goalId: goal.id, store: store) } }      // share progress, if the user allows it
        guard done, let g = store?.goals.first(where: { $0.id == goal.id }), g.progress.allDone else { return }
        // All of a goal's tasks done for the first time today: celebrate once per goal per day.
        let key = "celebrated.\(g.id.uuidString).\(LocalDay.today())"
        guard !UserDefaults.standard.bool(forKey: key) else { return }
        UserDefaults.standard.set(true, forKey: key)
        Analytics.track(.dayCompleted, ["tasks": g.progress.tasksToday, "streak": g.progress.streak])
        Task { try? await Task.sleep(for: .seconds(0.65)); celebrate = g.id }
    }

    /// A new deadline re-runs the Reality Check (not the plan). The date itself is exactly what the user picked.
    func recheck(_ g: GoalState) {
        realityBusy = true; realityError = false
        Task {
            defer { realityBusy = false }
            do {
                let current = store?.goals.first { $0.id == g.id } ?? g
                let f = try await APIClient.shared.reality(goal: current.statement, deadline: current.deadline, today: LocalDay.today(), routine: current.routine ?? "", tasks: current.tasks.map(\.title))
                store?.updateGoal(g.id, feasibility: f)
            } catch { realityError = true }          // keep the last verdict; say it couldn't refresh
        }
    }

    /// "Why I started" autosaves a moment after typing stops.
    func note(_ id: UUID, _ text: String) {
        noteTask?.cancel()
        noteTask = Task { try? await Task.sleep(for: .seconds(0.8)); if !Task.isCancelled { store?.updateGoal(id, realityNote: String(text.prefix(1000))) } }
    }

    /// Plans the next week of steps from what is done and still open. `done` says whether it worked (for the toast).
    func planSteps(_ g: GoalState, done: @escaping @MainActor (Bool, Bool) -> Void) {
        guard stepsBusy == nil else { return }
        stepsBusy = g.id
        Task {
            defer { stepsBusy = nil }
            do {
                let steps = try await APIClient.shared.nextSteps(goal: g.statement, deadline: g.deadline, today: LocalDay.today(), routine: g.routine ?? "", habits: g.habits, done: g.doneSteps, open: g.openSteps)
                store?.addSteps(goalId: g.id, steps)
                done(true, false)
            } catch APIError.offline { done(false, true) } catch { done(false, false) }
        }
    }

    func retryImages(_ g: GoalState) {
        Task {
            let kinds = ["future", "reality"].filter { g.images[$0]?.status != "done" }
            guard let dtos = try? await APIClient.shared.startImages(goalId: g.id, goal: g.statement, routine: g.routine ?? "", kinds: kinds) else { return }
            store?.saveImages(g.id, dtos)
            for _ in 0..<72 {
                try? await Task.sleep(for: .seconds(2.5))
                guard let now = try? await APIClient.shared.images(goalId: g.id) else { return }
                store?.saveImages(g.id, now)
                if !now.contains(where: { $0.status == "running" }) { return }
            }
        }
    }
}

enum TodaySheet: Identifiable, Equatable {
    case task(UUID), add(UUID), goal(UUID), archive(UUID), choose, limit, plus
    var id: String { switch self { case .task(let i): "t\(i)"; case .add(let i): "a\(i)"; case .goal(let i): "g\(i)"; case .archive(let i): "x\(i)"; case .choose: "c"; case .limit: "l"; case .plus: "p" } }
}

struct TodayView: View {
    @Environment(SessionStore.self) private var session
    @Environment(GoalStore.self) private var store
    @Environment(AppRouter.self) private var router
    @Environment(Toaster.self) private var toaster
    @Environment(SocialStore.self) private var social
    @Environment(BillingStore.self) private var billing
    @Environment(\.scenePhase) private var scenePhase
    @State private var model = TodayModel()
    @State private var sheet: TodaySheet?
    @State private var notificationsOff = false
    var now: Date = .now

    var body: some View {
        let goals = store.goals
        let open = goals.first { $0.id == model.openGoalId } ?? (goals.count == 1 ? goals[0] : nil)
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header(goals)
                if notificationsOff && !goals.isEmpty { notificationBanner }
                if !store.paused.isEmpty { PausedCard(count: store.paused.count) { sheet = .choose }.padding(.top, 16) }
                if goals.isEmpty { noGoals }
                else if let open { detail(goals, open) }
                else { overview(goals) }
            }
            .padding(.horizontal, Theme.Space.screen).padding(.top, 12).padding(.bottom, 32)
        }
        .background(Theme.bg)
        // Swipe from the left edge (or the VoiceOver escape gesture) goes back like a pushed screen would.
        .simultaneousGesture(DragGesture(minimumDistance: 20).onEnded { v in
            if v.startLocation.x < 30 && v.translation.width > 80 && abs(v.translation.height) < 60 { goBack(goals) }
        })
        .accessibilityAction(.escape) { goBack(goals) }
        .toolbar(.hidden, for: .navigationBar)
        .onAppear { model.store = store; model.social = social }
        .task(id: scenePhase) { if scenePhase == .active { store.reload(); model.refreshImages(for: store.goals.map(\.id)); notificationsOff = !(await NotificationScheduler.shared.authorized()) } }
        .onChange(of: store.goals.map(\.id)) { _, ids in model.refreshImages(for: ids) }
        .sheet(item: $sheet) { s in sheetView(s).presentationDetents([.large]).presentationDragIndicator(.visible).presentationBackground(Theme.s1) }
        .fullScreenCover(item: Binding(get: { model.celebrate.flatMap { id in goals.first { $0.id == id } } }, set: { if $0 == nil { model.celebrate = nil } })) { g in
            let next = goals.first { $0.id != g.id && $0.progress.tasksToday > $0.progress.doneToday }
            CelebrateView(goal: g, next: next, onNext: { model.celebrate = nil; if let next { model.open(next.id) } }, onJournal: { model.celebrate = nil; router.today.append(Route.journalEntry(goalId: g.id, localDate: LocalDay.today())) }, onClose: { model.celebrate = nil })
        }
        .fullScreenCover(item: $model.finished) { g in
            GoalCrossedView(goal: g, onNext: { model.finished = nil; addGoal() }, onClose: { model.finished = nil })
        }
    }

    /// Inside a goal: Reality Check → the goal's Today view → all goals.
    private func goBack(_ goals: [GoalState]) {
        withAnimation(.easeOut(duration: 0.2)) {
            if model.view != .today { model.view = .today } else if model.openGoalId != nil && goals.count > 1 { model.open(nil) }
        }
    }

    private func complete(_ g: GoalState) {
        store.complete(g.id); sheet = nil
        if model.openGoalId == g.id { model.open(nil) }
        model.finished = g
    }

    private func archive(_ id: UUID) {
        store.archive(id); sheet = nil; if model.openGoalId == id { model.open(nil) }
        toaster.show(String(localized: "Goal archived. Its images and history are kept."))
    }

    /// Plus lifts the three-goal limit (the server enforces it too).
    private func addGoal() { if store.goals.count >= GoalStore.freeGoalLimit && !billing.active.contains("plus") { sheet = .limit } else { router.today.append(Route.addGoal) } }

    @ViewBuilder private func header(_ goals: [GoalState]) -> some View {
        let hour = Calendar.current.component(.hour, from: now)
        let name = session.user?.displayName ?? ""
        let streak = goals.map(\.progress.streak).max() ?? 0
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 6) {
                Text(now.formatted(.dateTime.weekday(.wide).month(.wide).day()).uppercased()).bridgeLabel(11)
                Group {
                    if (3..<12).contains(hour) { Text("Good morning, \(name)") } else if (12..<17).contains(hour) { Text("Good afternoon, \(name)") } else { Text("Good evening, \(name)") }
                }
                .font(BFont.display(25)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
            }
            Spacer()
            if !goals.isEmpty {
                HStack(spacing: 5) {
                    Image(systemName: "flame.fill").foregroundStyle(Theme.acc)
                    Text("\(streak)").font(BFont.label(14)).fontWeight(.semibold).foregroundStyle(Theme.ink)
                }
                .padding(.leading, 9).padding(.trailing, 12).frame(minHeight: 40).background(Theme.s1, in: Capsule()).overlay(Capsule().stroke(Theme.line, lineWidth: 1))
                .accessibilityElement(children: .ignore).accessibilityLabel("Current streak: ^[\(streak) day](inflect: true)")
            }
        }
        .padding(.top, 6)
    }

    private var notificationBanner: some View {
        HStack(spacing: 8) {
            Text("Reminders are off. Turn on notifications to get your daily nudge.").font(BFont.body(13)).foregroundStyle(Theme.ink2)
            Spacer()
            Button("Settings") { if let u = URL(string: UIApplication.openNotificationSettingsURLString) { UIApplication.shared.open(u) } }
                .font(BFont.body(13, .semibold)).foregroundStyle(Theme.ink).frame(minHeight: 44)
        }
        .padding(.leading, 14).padding(.trailing, 10).padding(.vertical, 4)
        .background(Theme.s1, in: RoundedRectangle(cornerRadius: 14, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Theme.line, lineWidth: 1))
        .padding(.top, 14)
    }

    private var noGoals: some View {
        VStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 6) {
                Text("Your plan starts here").font(BFont.display(20)).foregroundStyle(Theme.ink)
                Text("Add a goal and pick a date. Your daily plan shows up on this screen.").font(BFont.body(14)).foregroundStyle(Theme.mut)
            }
            .padding(Theme.Space.card).frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.s1, in: RoundedRectangle(cornerRadius: 20, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(Theme.line, lineWidth: 1))
            addGoalTile
        }
        .padding(.top, 20)
    }

    private var addGoalTile: some View {
        Button(action: addGoal) {
            Label("Add a goal", systemImage: "plus").font(BFont.body(14, .medium)).foregroundStyle(Theme.ink2).frame(maxWidth: .infinity, minHeight: 56)
                .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(Theme.line2, style: StrokeStyle(lineWidth: 1, dash: [5, 4])))
        }
    }

    private func overview(_ goals: [GoalState]) -> some View {
        VStack(spacing: 12) {
            HStack(alignment: .lastTextBaseline) {
                Text("Your goals").font(BFont.display(19)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                Spacer()
                Text("\(goals.count) of 3").font(BFont.body(13)).foregroundStyle(Theme.mut)
            }
            ForEach(goals) { g in GoalCard(goal: g) { model.open(g.id) } }
            addGoalTile
        }
        .padding(.top, 20)
    }

    @ViewBuilder private func detail(_ goals: [GoalState], _ g: GoalState) -> some View {
        if goals.count > 1 {
            HStack {
                Button { model.open(nil) } label: { Label("All goals", systemImage: "chevron.left").font(BFont.body(15, .medium)).foregroundStyle(Theme.ink).frame(minHeight: 44) }
                Spacer()
                Text(g.label).font(BFont.display(16)).foregroundStyle(Theme.ink).lineLimit(1)
                Button { sheet = .goal(g.id) } label: { Image(systemName: "pencil").foregroundStyle(Theme.mut).frame(width: 44, height: 44) }.accessibilityLabel("Edit goal")
            }
            .padding(.top, 14)
        } else {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    Button { sheet = .goal(g.id) } label: {
                        HStack(spacing: 9) {
                            if g.progress.allDone { Image(systemName: "checkmark.circle.fill").foregroundStyle(Theme.ok).font(.system(size: 15)) } else { Circle().fill(Theme.acc).frame(width: 8, height: 8) }
                            Text(g.label).font(BFont.body(14, .semibold)).foregroundStyle(Theme.ink).lineLimit(1)
                            Text("\(g.progress.doneToday)/\(g.progress.tasksToday)").font(BFont.label(11)).foregroundStyle(Theme.mut)
                            Image(systemName: "pencil").font(.system(size: 13)).foregroundStyle(Theme.mut)
                        }
                        .padding(.leading, 12).padding(.trailing, 14).frame(minHeight: 46).background(Theme.accTint, in: Capsule()).overlay(Capsule().stroke(Theme.line2, lineWidth: 1))
                    }
                    .accessibilityHint("Edit goal")
                    Button(action: addGoal) {
                        Label("Goal", systemImage: "plus").font(BFont.body(13, .medium)).foregroundStyle(Theme.ink2).padding(.horizontal, 16).frame(minHeight: 46).overlay(Capsule().stroke(Theme.line, lineWidth: 1))
                    }
                    .accessibilityLabel("Add a goal")
                }
            }
            .padding(.top, 16)
        }
        // Today | Reality Check | Community. Community opens its own screen inside the goal.
        HStack(spacing: 4) {
            ForEach([(GoalView.today, "Today"), (GoalView.reality, "Reality Check")] as [(GoalView, LocalizedStringKey)], id: \.0) { v, label in
                let on = model.view == v
                Button { withAnimation(.easeOut(duration: 0.2)) { model.view = v } } label: {
                    Text(label).font(BFont.body(14, .semibold)).foregroundStyle(on ? Theme.ink : Theme.mut).frame(maxWidth: .infinity, minHeight: 40)
                        .background(on ? Theme.segOn : .clear, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).stroke(on ? Theme.line2 : .clear, lineWidth: 1))
                }
                .accessibilityAddTraits(on ? [.isSelected] : [])
            }
            Button { router.today.append(Route.community(goalId: g.id)) } label: {
                Label("Community", systemImage: "person.2.fill").font(BFont.body(14, .semibold)).foregroundStyle(Theme.mut).frame(maxWidth: .infinity, minHeight: 40)
            }
        }
        .padding(4).background(Theme.s0, in: RoundedRectangle(cornerRadius: 14, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Theme.line, lineWidth: 1))
        .padding(.top, 12)
        if model.view == .today { todayView(g) } else { RealityView(goal: g, model: model) }
    }

    private func todayView(_ g: GoalState) -> some View {
        let p = g.progress
        let md = Date.FormatStyle().month(.abbreviated).day()
        return VStack(alignment: .leading, spacing: 18) {
            if p.overdue {
                RecapCard(goal: g, onComplete: { complete(g) }, onExtend: { sheet = .goal(g.id) }, onArchive: { sheet = .archive(g.id) }).padding(.top, 22)
            } else if p.away {
                AwayCard { withAnimation(.easeOut(duration: 0.2)) { model.view = .reality } }.padding(.top, 22)
            }
            VStack(spacing: 14) {
                ZStack(alignment: .top) {
                    BridgeArc(progress: p.fraction, today: p.todayFraction).aspectRatio(340.0 / 196.0, contentMode: .fit).frame(maxWidth: 340)
                    VStack(spacing: 6) {
                        Text("DAY \(p.day) OF \(p.total)").bridgeLabel(10)
                        Text(p.fraction, format: .percent.precision(.fractionLength(1))).font(BFont.display(44)).tracking(-1.3).foregroundStyle(Theme.ink)
                    }
                    .padding(.top, 70)
                }
                .accessibilityElement(children: .ignore).accessibilityLabel(Text("Progress across the bridge: \(p.fraction.formatted(.percent.precision(.fractionLength(1))))"))
                HStack {
                    Text(Day.parse(g.startDate)!.formatted(md).uppercased()).bridgeLabel(9.5)
                    Spacer()
                    Text(Day.parse(g.deadline)!.formatted(md).uppercased()).bridgeLabel(9.5)
                }
                .padding(.horizontal, 12)
            }
            .padding(.horizontal, 10).padding(.top, 18).padding(.bottom, 16).frame(maxWidth: .infinity)
            .background(Theme.s1, in: RoundedRectangle(cornerRadius: 24, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).stroke(Theme.line, lineWidth: 1))
            .padding(.top, 22)
            HStack(spacing: 8) {
                StatTile(label: "STREAK", bg: Theme.goalCards[0]) { (Text("\(p.streak)").font(BFont.display(22)) + Text(p.streak == 1 ? " day" : " days").font(BFont.body(12)).foregroundColor(Theme.mut)).foregroundStyle(Theme.ink) }
                StatTile(label: "DAYS LEFT", bg: Theme.goalCards[1]) { Text("\(p.daysLeft)").font(BFont.display(22)).foregroundStyle(Theme.ink) }
                StatTile(label: "DRIFT", bg: Theme.goalCards[2]) {
                    let d = p.drift
                    Text(d.map { $0 > 0 ? "+\($0)%" : $0 < 0 ? "−\(abs($0))%" : "0%" } ?? "—").font(BFont.display(22)).foregroundStyle((d ?? 0) < 0 ? Theme.warn : Theme.ok)
                    Text(d == nil || d == 0 ? "on pace" : d! < 0 ? "behind pace" : "ahead of pace").font(BFont.body(11)).foregroundStyle(Theme.mut)
                }
            }
            .fixedSize(horizontal: false, vertical: true)
            if p.allDone {
                HStack(spacing: 14) {
                    RemoteImage(url: g.images["future"]?.url).frame(width: 56, height: 68).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous)).accessibilityHidden(true)
                    // "Lock it in" turns into "Locked in." once today's reflection has text or a mood.
                    let locked = g.journaledToday
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 6) {
                            if locked { Image(systemName: "lock.fill").foregroundStyle(Theme.ok).font(.system(size: 14)) }
                            Text(locked ? "Locked in." : "Today's span is complete.").font(BFont.display(15)).foregroundStyle(Theme.ink)
                        }
                        Text(locked ? "Reflection saved. See you tomorrow." : "Two minutes in the Journal locks it in.").font(BFont.body(13)).foregroundStyle(Theme.mut)
                    }
                    Spacer()
                    Button { router.today.append(Route.journalEntry(goalId: g.id, localDate: LocalDay.today())) } label: {
                        Image(systemName: locked ? "book" : "arrow.right").foregroundStyle(locked ? Theme.ok : Theme.onAcc).frame(width: 44, height: 44).background(locked ? Theme.okTint : Theme.acc, in: Circle())
                    }
                    .accessibilityLabel(locked ? "Read your entry" : "Open Journal")
                }
                .padding(12).background(Theme.s1, in: RoundedRectangle(cornerRadius: 18, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(g.journaledToday ? Theme.ok : Theme.accLine, lineWidth: 1))
            }
            HStack(alignment: .lastTextBaseline) {
                Text("Today's plan").font(BFont.display(19)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                Spacer()
                Text("\(p.doneToday)/\(p.tasksToday)").font(BFont.label(12)).foregroundStyle(Theme.mut)
            }
            .padding(.top, 4)
            if g.tasks.isEmpty {
                VStack(spacing: 10) {
                    Image(systemName: "checklist").font(.system(size: 28)).foregroundStyle(Theme.mut)
                    Text("No tasks for today").font(BFont.display(16)).foregroundStyle(Theme.ink)
                    Text("Add one yourself, or ask your Coach to rebuild your plan.").font(BFont.body(13)).foregroundStyle(Theme.mut).multilineTextAlignment(.center)
                }
                .padding(.vertical, 28).padding(.horizontal, 20).frame(maxWidth: .infinity)
                .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Theme.line, style: StrokeStyle(lineWidth: 1, dash: [5, 4])))
            }
            VStack(spacing: 8) { ForEach(g.tasks) { t in TaskRow(task: t, onToggle: { model.toggle(t, in: g) }, onOpen: { sheet = .task(t.id) }) } }
            Button { sheet = .add(g.id) } label: {
                Label("Add task", systemImage: "plus").font(BFont.body(14, .medium)).foregroundStyle(Theme.ink2).frame(maxWidth: .infinity, minHeight: 52)
                    .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(Theme.line, style: StrokeStyle(lineWidth: 1, dash: [5, 4])))
            }
            // Every step done (or a goal from before steps): the AI plans the next week from where you are.
            if g.needsSteps && !p.overdue {
                let busy = model.stepsBusy == g.id
                Button {
                    model.planSteps(g) { ok, offline in
                        toaster.show(ok ? String(localized: "Next steps added.") : offline ? String(localized: "You're offline. Try again when you reconnect.") : String(localized: "Couldn't plan the next steps. Try again."), ok ? .ok : .warn)
                    }
                } label: {
                    HStack(spacing: 8) {
                        if busy { ProgressView().tint(Theme.ink2) } else { Image(systemName: "calendar.badge.plus").foregroundStyle(Theme.ink2) }
                        Text(busy ? "Planning your next steps…" : "Plan my next steps").font(BFont.body(14, .medium)).foregroundStyle(Theme.ink2)
                    }
                    .frame(maxWidth: .infinity, minHeight: 52)
                    .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Theme.line, lineWidth: 1))
                }
                .disabled(busy)
            }
        }
    }

    @ViewBuilder private func sheetView(_ s: TodaySheet) -> some View {
        switch s {
        case .limit:
            LimitSheet(onManage: { sheet = nil; model.open(nil) }, onDismiss: { sheet = nil }, onUpgrade: { sheet = .plus })
        case .plus:
            PaywallSheet(product: .plus) { sheet = nil }
        case .add(let goalId):
            TaskSheet(task: nil) { title, notes, priority, time in
                store.addTask(goalId: goalId, title: title, notes: notes, priority: priority, time: time); sheet = nil
                toaster.show(String(localized: "Added to today's plan."), .ok)
            } onToggle: { _ in } onDelete: {}
        case .task(let id):
            if let t = store.goals.flatMap(\.tasks).first(where: { $0.id == id }), let g = store.goals.first(where: { $0.tasks.contains(t) }) {
                TaskSheet(task: t) { title, notes, priority, time in
                    store.updateTask(id, title: title, notes: notes, priority: priority, time: time); sheet = nil
                    toaster.show(String(localized: "Task updated."), .ok)
                } onToggle: { _ in model.toggle(t, in: g); sheet = nil } onDelete: {
                    store.deleteTask(id); sheet = nil; toaster.show(String(localized: "Task deleted."))
                }
            }
        case .goal(let id):
            if let g = store.goals.first(where: { $0.id == id }) {
                EditGoalSheet(goal: g) { label, deadline in
                    let changed = deadline != g.deadline
                    store.updateGoal(id, label: label, deadline: deadline); sheet = nil
                    toaster.show(String(localized: "Goal updated."), .ok)
                    if changed, let fresh = store.goals.first(where: { $0.id == id }) { model.recheck(fresh) }
                } onArchive: {
                    archive(id)
                } onComplete: {
                    complete(g)
                }
            }
        case .choose:
            let plus = billing.active.contains("plus")
            ChooseGoalsView(goals: store.goals.map { GoalChoice(id: $0.id, label: $0.label, statement: $0.statement) } + store.paused, initial: Set(store.goals.map(\.id)),
                            max: plus ? Int.max : GoalStore.freeGoalLimit, onSave: { keep in
                store.chooseActive(keep); sheet = nil
                if let id = model.openGoalId, !keep.contains(id) { model.open(nil) }
            }, onUpgrade: plus ? nil : { sheet = .plus })
        case .archive(let id):
            ArchiveGoalSheet(onArchive: { archive(id) }, onCancel: { sheet = nil })
        }
    }
}

/// One card per goal: radius 24, colour rotates gc1/gc2/gc3.
struct GoalCard: View {
    let goal: GoalState
    var onOpen: () -> Void
    var body: some View {
        let p = goal.progress
        Button(action: onOpen) {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text(goal.label).font(BFont.body(14, .medium))
                    Spacer()
                    Text("Day \(p.day) / \(p.total)").font(BFont.body(12, .medium))
                }
                Spacer(minLength: 28)
                HStack(alignment: .lastTextBaseline) {
                    Text(p.fraction, format: .percent.precision(.fractionLength(1))).font(BFont.body(38, .medium)).tracking(-0.76)
                    Spacer()
                    Group { if p.tasksToday == 0 { Text("No tasks") } else { Text("\(p.doneToday) of \(p.tasksToday) today") } }.font(BFont.body(13, .medium))
                }
                GeometryReader { geo in Capsule().fill(Theme.goalTrack).overlay(alignment: .leading) { Capsule().fill(Theme.goalInk).frame(width: geo.size.width * p.fraction) } }.frame(height: 3).padding(.vertical, 10)
                HStack {
                    Text("Ends \(Day.parse(goal.deadline)!.formatted(date: .abbreviated, time: .omitted))").font(BFont.body(12))
                    Spacer()
                    Image(systemName: "arrow.right")
                }
            }
            .foregroundStyle(Theme.goalInk).padding(18).frame(maxWidth: .infinity, minHeight: 188, alignment: .leading)
            .background(Theme.goalCards[goal.colorIndex % 3], in: RoundedRectangle(cornerRadius: Theme.Radius.goalCard, style: .continuous))
        }
        .buttonStyle(PressScale())
    }
}

struct PressScale: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { configuration.label.scaleEffect(configuration.isPressed ? 0.98 : 1).animation(.easeOut(duration: 0.12), value: configuration.isPressed) }
}

private struct StatTile<V: View>: View {
    let label: LocalizedStringKey
    let bg: Color
    @ViewBuilder var value: V
    var body: some View {
        VStack(alignment: .leading, spacing: 6) { Text(label).bridgeLabel(9.5).lineLimit(1); value }
            .padding(12).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .background(Theme.s1, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Theme.line, lineWidth: 1))
            .accessibilityElement(children: .combine)
    }
}

/// The bridge: dashed full span, progress arc, today's inner arc and a marker where you stand. Geometry from the prototype (340×196).
struct BridgeArc: View {
    let progress: Double, today: Double
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        Canvas { ctx, size in
            let u = size.width / 340, cx = 170 * u, cy = 176 * u, r = 146 * u, r2 = 128 * u
            func arc(_ radius: CGFloat, _ fraction: Double) -> Path {
                Path { p in p.addArc(center: CGPoint(x: cx, y: cy), radius: radius, startAngle: .degrees(180), endAngle: .degrees(180 + 180 * fraction), clockwise: false) }
            }
            ctx.stroke(Path { p in p.move(to: CGPoint(x: 6 * u, y: cy + 4 * u)); p.addLine(to: CGPoint(x: 334 * u, y: cy + 4 * u)) }, with: .color(Theme.ink), lineWidth: 1.5 * u)
            ctx.stroke(arc(r, 1), with: .color(Theme.mut2), style: StrokeStyle(lineWidth: 1.5 * u, dash: [3 * u, 6 * u]))
            if progress > 0 { ctx.stroke(arc(r, progress), with: .color(Theme.prog), style: StrokeStyle(lineWidth: 6 * u, lineCap: .round)) }
            ctx.stroke(arc(r2, 1), with: .color(Theme.track), style: StrokeStyle(lineWidth: 10 * u, lineCap: .round))
            if today > 0 { ctx.stroke(arc(r2, today), with: .color(Theme.today), style: StrokeStyle(lineWidth: 10 * u, lineCap: .round)) }
            let a = Double.pi * (1 - progress)
            let o = CGPoint(x: cx + r * cos(a), y: cy - r * sin(a))
            ctx.fill(Path(ellipseIn: CGRect(x: o.x - 9 * u, y: o.y - 9 * u, width: 18 * u, height: 18 * u)), with: .color(Theme.ink))
            ctx.fill(Path(ellipseIn: CGRect(x: o.x - 4 * u, y: o.y - 4 * u, width: 8 * u, height: 8 * u)), with: .color(Theme.prog))
        }
        .animation(reduceMotion ? nil : .timingCurve(0.2, 0.8, 0.2, 1, duration: 0.9), value: progress)
    }
}

/// Checkbox (with pop + light haptic), title, priority pill and time. Tapping the row opens the details sheet.
struct TaskRow: View {
    let task: PlanTask
    var onToggle: () -> Void
    var onOpen: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var pop = false
    var body: some View {
        HStack(spacing: 12) {
            Button {
                if !task.done {
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    if !reduceMotion { withAnimation(.easeOut(duration: 0.126)) { pop = true }; Task { try? await Task.sleep(for: .milliseconds(126)); withAnimation(.easeOut(duration: 0.154)) { pop = false } } }
                }
                onToggle()
            } label: {
                Circle().fill(task.done ? Theme.okFill : .clear).overlay(Circle().stroke(task.done ? Theme.okFill : Theme.ink2, lineWidth: 2)).frame(width: 26, height: 26)
                    .overlay { if task.done { Image(systemName: "checkmark").font(.system(size: 13, weight: .bold)).foregroundStyle(Theme.onOk) } }
                    .scaleEffect(pop ? 1.3 : 1).frame(width: 44, height: 44)
            }
            .accessibilityLabel(task.done ? "Mark \"\(task.title)\" not done" : "Mark \"\(task.title)\" done")
            .accessibilityValue(task.done ? "done" : "not done")
            Button(action: onOpen) {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(task.title).font(BFont.body(15, .medium)).foregroundStyle(task.done ? Theme.mut : Theme.ink).strikethrough(task.done).multilineTextAlignment(.leading)
                        // A step has no priority: it is today's action. One carried over says since when.
                        if !task.isStep || task.carriedFrom != nil || task.timeOfDay != nil || !task.links.isEmpty {
                            HStack(spacing: 8) {
                                if !task.isStep, let p = Theme.Priority(rawValue: task.priority) { PriorityPill(priority: p) }
                                if let c = task.carriedFrom, let d = Day.parse(c) { Text("From \(d.formatted(.dateTime.weekday(.abbreviated)))").font(BFont.body(12)).foregroundStyle(Theme.mut) }
                                if let t = task.timeOfDay { Text(t).font(BFont.body(12)).foregroundStyle(Theme.mut) }
                                if !task.links.isEmpty { Image(systemName: "link").font(.system(size: 12, weight: .medium)).foregroundStyle(Theme.mut).accessibilityLabel("Has links") }
                            }
                        }
                    }
                    Spacer()
                    Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.mut2).accessibilityHidden(true)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(.leading, 8).padding(.trailing, 12).padding(.vertical, 12).frame(minHeight: 68)
        .background(Theme.s1, in: RoundedRectangle(cornerRadius: 16, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Theme.line, lineWidth: 1))
    }
}

/// Opens in the browser (or the YouTube app). Says where it goes: the site, or that it is a search.
struct LinkRow: View {
    let link: LinkDTO
    @Environment(\.openURL) private var openURL
    private var source: String {
        var host = URL(string: link.url)?.host() ?? ""
        for prefix in ["www.", "m."] where host.hasPrefix(prefix) { host.removeFirst(prefix.count) }
        if link.url.contains("youtube.com/results") { return String(localized: "YouTube search") }
        if link.url.contains("google.com/search") { return String(localized: "Web search") }
        if host == "youtube.com" || host == "youtu.be" { return String(localized: "YouTube") }
        return host
    }
    var body: some View {
        Button { if let u = URL(string: link.url) { openURL(u) } } label: {
            HStack(spacing: 12) {
                Image(systemName: "link").font(.system(size: 15, weight: .medium)).foregroundStyle(Theme.ink2).accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(link.title).font(BFont.body(14, .medium)).foregroundStyle(Theme.ink).lineLimit(2).multilineTextAlignment(.leading)
                    if !source.isEmpty { Text(source).font(BFont.body(12)).foregroundStyle(Theme.mut).lineLimit(1) }
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.mut2).accessibilityHidden(true)
            }
            .padding(.horizontal, 14).padding(.vertical, 8).frame(maxWidth: .infinity, minHeight: 52)
            .background(Theme.bg, in: RoundedRectangle(cornerRadius: 14, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Theme.line, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityHint(Text("Opens \(source)"))
    }
}

struct RealityView: View {
    let goal: GoalState
    let model: TodayModel
    @State private var note: String = ""
    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            if let f = goal.feasibility {
                FeasibilityCard(f: f, deadline: goal.deadline, onUseDate: { d in model.store?.updateGoal(goal.id, deadline: d); if let fresh = model.store?.goals.first(where: { $0.id == goal.id }) { model.recheck(fresh) } },
                                note: model.realityBusy ? String(localized: "Checking your date…") : model.realityError ? String(localized: "Couldn't refresh the check. Showing the last one.") : nil)
            }
            let img = goal.images["reality"]
            ZStack(alignment: .bottomLeading) {
                RemoteImage(url: img?.status == "done" ? img?.url : nil).frame(maxWidth: .infinity).frame(height: 430).clipped()
                LinearGradient(stops: [.init(color: .clear, location: 0.5), .init(color: Theme.bg.opacity(0.92), location: 1)], startPoint: .top, endPoint: .bottom)
                VStack(alignment: .leading, spacing: 6) {
                    if img?.status == "running" { Text("Your image is still being made.").font(BFont.body(13)).foregroundStyle(Theme.mut) }
                    if img == nil || img?.status == "failed" {
                        Text("The image couldn't be made.").font(BFont.body(13)).foregroundStyle(Theme.mut)
                        Button("Try again") { model.retryImages(goal) }.font(BFont.body(13, .semibold)).foregroundStyle(Theme.ink).frame(minHeight: 44)
                    }
                    Text("If nothing changes.").font(BFont.display(30)).foregroundStyle(Theme.ink)
                    Text("This is where your Day 1 routine leads.").font(BFont.body(14)).foregroundStyle(Theme.ink2)
                }
                .padding(18)
            }
            .frame(height: 430)
            .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).stroke(Theme.line, lineWidth: 1))
            if let routine = goal.routine, !routine.isEmpty {
                VStack(alignment: .leading, spacing: 10) {
                    Text("YOUR ROUTINE, DAY 1").bridgeLabel(11)
                    Text(routine).font(BFont.body(15)).lineSpacing(5).foregroundStyle(Theme.ink2).padding(.leading, 14).overlay(alignment: .leading) { Rectangle().fill(Theme.line).frame(width: 1) }
                }
            }
            VStack(alignment: .leading, spacing: 10) {
                Text("WHY I STARTED").bridgeLabel(11)
                BigTextArea(text: Binding(get: { note }, set: { note = $0; model.note(goal.id, $0) }), placeholder: "Write a note to yourself for the hard days.", minHeight: 96).accessibilityLabel("Why I started")
            }
            Button("Back to today's plan") { withAnimation(.easeOut(duration: 0.2)) { model.view = .today } }.buttonStyle(SecondaryButtonStyle())
        }
        .padding(.top, 16)
        .onAppear { note = goal.realityNote ?? "" }
    }
}

// MARK: Sheets

struct TaskSheet: View {
    let task: PlanTask?
    var onSave: (String, String, String, String) -> Void
    var onToggle: (Bool) -> Void
    var onDelete: () -> Void
    @State private var title = ""
    @State private var notes = ""
    @State private var priority = "Medium"
    @State private var time = ""
    @State private var error = false
    @State private var confirm = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    Text(task == nil ? "Add a task" : "Task details").font(BFont.display(20)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                    Spacer()
                }
                if task?.readOnly == true { Text("Set by your alliance lead. Only they can change it.").font(BFont.body(13)).foregroundStyle(Theme.mut) }
                BridgeField(label: "TITLE", placeholder: "e.g. Stretch for 10 minutes", text: Binding(get: { title }, set: { title = String($0.prefix(120)); error = false }),
                            error: error ? String(localized: "Give the task a name.") : nil, capitalization: .sentences)
                VStack(alignment: .leading, spacing: 7) {
                    Text("NOTES").bridgeLabel(11)
                    BigTextArea(text: $notes, placeholder: "Optional", minHeight: 76, limit: 2000)
                }
                HStack(alignment: .top, spacing: 10) {
                    VStack(alignment: .leading, spacing: 7) {
                        Text("PRIORITY").bridgeLabel(11)
                        Picker("Priority", selection: $priority) { Text("High").tag("High"); Text("Medium").tag("Medium"); Text("Low").tag("Low") }.pickerStyle(.segmented)
                    }
                    .frame(maxWidth: .infinity)
                    BridgeField(label: "WHEN", placeholder: "Any time", text: Binding(get: { time }, set: { time = String($0.prefix(24)) })).frame(maxWidth: 140)
                }
                if let r = task?.rationale {
                    Text(r).font(BFont.body(13)).foregroundStyle(Theme.mut).frame(maxWidth: .infinity, alignment: .leading)
                }
                if let links = task?.links, !links.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("LINKS").bridgeLabel(11)
                        ForEach(links, id: \.self) { LinkRow(link: $0) }
                    }
                }
                let save = { if title.trimmingCharacters(in: .whitespaces).count < 2 { error = true } else { onSave(title, notes, priority, time) } }
                if task == nil {
                    Button("Add to today", action: save).buttonStyle(PrimaryButtonStyle())
                } else if confirm {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Delete this task?").font(BFont.display(16)).foregroundStyle(Theme.ink)
                        Text("It will be removed from today's plan. This can't be undone.").font(BFont.body(13)).foregroundStyle(Theme.ink2)
                        HStack(spacing: 8) {
                            Button("Cancel") { confirm = false }.buttonStyle(SecondaryButtonStyle())
                            Button("Delete", action: onDelete).font(BFont.body(14, .semibold)).foregroundStyle(Theme.ink).frame(maxWidth: .infinity, minHeight: 48).background(Theme.bad, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        }
                    }
                    .padding(16).background(Theme.badTint, in: RoundedRectangle(cornerRadius: 16, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Theme.bad, lineWidth: 1))
                } else if let task {
                    VStack(spacing: 8) {
                        if !task.readOnly { Button("Save changes", action: save).buttonStyle(PrimaryButtonStyle()) }
                        HStack(spacing: 8) {
                            Button(task.done ? "Mark not done" : "Mark done") { onToggle(!task.done) }.buttonStyle(SecondaryButtonStyle())
                            if !task.readOnly {
                                Button("Delete") { confirm = true }.font(BFont.body(14, .medium)).foregroundStyle(Theme.bad).frame(maxWidth: .infinity, minHeight: 48)
                                    .overlay(RoundedRectangle(cornerRadius: Theme.Radius.input, style: .continuous).stroke(Theme.line, lineWidth: 1))
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 20).padding(.top, 24).padding(.bottom, 24)
        }
        .onAppear { title = task?.title ?? ""; notes = task?.notes ?? ""; priority = task?.priority ?? "Medium"; time = task?.timeOfDay ?? "" }
    }
}

struct EditGoalSheet: View {
    let goal: GoalState
    var onSave: (String, String) -> Void
    var onArchive: () -> Void
    var onComplete: (() -> Void)?
    @State private var label = ""
    @State private var deadline: String?
    @State private var error = false
    @State private var confirm = false
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Edit goal").font(BFont.display(20)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                Text(goal.statement).font(BFont.body(14)).foregroundStyle(Theme.mut)
                BridgeField(label: "NAME", placeholder: "", text: Binding(get: { label }, set: { label = String($0.prefix(24)); error = false }), error: error ? String(localized: "Give the goal a short name.") : nil, capitalization: .sentences)
                DateFieldView(label: "TARGET DATE", value: $deadline, minDays: Deadlines.minDaysEdit)
                if Deadlines.check(deadline, today: LocalDay.today(), minDays: Deadlines.minDaysEdit) != nil { DeadlineMessage(deadline: deadline, minDays: Deadlines.minDaysEdit) }
                if confirm {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Archive this goal?").font(BFont.display(16)).foregroundStyle(Theme.ink)
                        Text("Its tasks leave your daily plan. Images, journal entries and history are kept.").font(BFont.body(13)).foregroundStyle(Theme.ink2)
                        HStack(spacing: 8) {
                            Button("Cancel") { confirm = false }.buttonStyle(SecondaryButtonStyle())
                            Button("Archive", action: onArchive).font(BFont.body(14, .semibold)).foregroundStyle(Theme.ink).frame(maxWidth: .infinity, minHeight: 48).background(Theme.bad, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        }
                    }
                    .padding(16).background(Theme.badTint, in: RoundedRectangle(cornerRadius: 16, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Theme.bad, lineWidth: 1))
                } else {
                    Button("Save changes") {
                        if label.trimmingCharacters(in: .whitespaces).count < 2 { error = true }
                        else if let deadline, Deadlines.check(deadline, today: LocalDay.today(), minDays: Deadlines.minDaysEdit) == nil { onSave(label, deadline) }
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    if let onComplete {
                        Button("Mark goal complete", action: onComplete).font(BFont.body(14, .medium)).foregroundStyle(Theme.ok).frame(maxWidth: .infinity, minHeight: 48)
                            .overlay(RoundedRectangle(cornerRadius: Theme.Radius.input, style: .continuous).stroke(Theme.ok, lineWidth: 1))
                    }
                    Button("Archive goal") { confirm = true }.font(BFont.body(14, .medium)).foregroundStyle(Theme.bad).frame(maxWidth: .infinity, minHeight: 48)
                        .overlay(RoundedRectangle(cornerRadius: Theme.Radius.input, style: .continuous).stroke(Theme.line, lineWidth: 1))
                }
            }
            .padding(.horizontal, 20).padding(.top, 24).padding(.bottom, 24)
        }
        .onAppear { label = goal.label; deadline = goal.deadline }
    }
}

struct LimitSheet: View {
    var onManage: () -> Void
    var onDismiss: () -> Void
    var onUpgrade: (() -> Void)?
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("3").font(BFont.display(22)).foregroundStyle(Theme.ink).frame(width: 48, height: 48).background(Theme.accTint, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            Text("Three goals at a time").font(BFont.display(20)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
            Text("Each goal adds tasks to every day. Finish or archive one to make room for the next.").font(BFont.body(15)).foregroundStyle(Theme.ink2)
            Button("Manage my goals", action: onManage).buttonStyle(PrimaryButtonStyle())
            if let onUpgrade { Button("Get more goals with Plus", action: onUpgrade).buttonStyle(SecondaryButtonStyle()) }
            Button("Not now", action: onDismiss).buttonStyle(SecondaryButtonStyle())
            Spacer()
        }
        .padding(.horizontal, 20).padding(.top, 28)
        .presentationDetents([.medium])
    }
}

/// Plus ended with more than three goals: the extras are paused and read-only until the user picks.
struct PausedCard: View {
    let count: Int
    var onChoose: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Some goals are paused").font(BFont.display(16)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
            Text("Plus ended, so ^[\(count) goal](inflect: true) became read-only. History is kept. Choose which goals stay active.").font(BFont.body(13)).foregroundStyle(Theme.ink2)
            Button(action: onChoose) {
                HStack(spacing: 6) { Text("Choose active goals"); Image(systemName: "arrow.right").font(.system(size: 14)) }
                    .font(BFont.body(14, .semibold)).foregroundStyle(Theme.ink).frame(minHeight: 44)
            }
        }
        .padding(.horizontal, 16).padding(.top, 14).padding(.bottom, 6).frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.warnTint, in: RoundedRectangle(cornerRadius: 18, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Theme.warn, lineWidth: 1))
    }
}

/// Pick up to `max` goals to keep active; the rest stay paused with their history.
struct ChooseGoalsView: View {
    let goals: [GoalChoice]
    let initial: Set<UUID>
    let max: Int
    var onSave: (Set<UUID>) -> Void
    var onUpgrade: (() -> Void)?
    @State private var keep: Set<UUID>?
    var body: some View {
        let chosen = keep ?? initial
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("Choose your active goals").font(BFont.display(20)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                Text("Up to \(GoalStore.freeGoalLimit) goals can be active without Plus. The others stay paused and read-only, with their history kept.").font(BFont.body(14)).foregroundStyle(Theme.ink2)
                VStack(spacing: 8) {
                    ForEach(goals) { g in
                        let on = chosen.contains(g.id)
                        let enabled = on || chosen.count < max
                        Button { keep = on ? chosen.subtracting([g.id]) : chosen.union([g.id]) } label: {
                            HStack(spacing: 12) {
                                Image(systemName: on ? "checkmark.square.fill" : "square").font(.system(size: 22)).foregroundStyle(on ? Theme.ink : Theme.mut2)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(g.label).font(BFont.body(15, .semibold)).foregroundStyle(enabled ? Theme.ink : Theme.mut)
                                    Text(g.statement).font(BFont.body(12)).foregroundStyle(Theme.mut).lineLimit(2)
                                }
                                Spacer()
                            }
                            .padding(.horizontal, 14).padding(.vertical, 10).frame(minHeight: 56)
                            .background(on ? Theme.accTint : Theme.s1, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(on ? Theme.line2 : Theme.line, lineWidth: 1))
                        }
                        .buttonStyle(.plain).disabled(!enabled)
                        .accessibilityAddTraits(on ? [.isSelected] : [])
                    }
                }
                if max != Int.max { Text("\(chosen.count) OF \(max) CHOSEN").bridgeLabel(11) }
                Button("Save changes") { onSave(chosen) }.buttonStyle(PrimaryButtonStyle()).disabled(chosen.isEmpty)
                if let onUpgrade { Button("Get more goals with Plus", action: onUpgrade).buttonStyle(SecondaryButtonStyle()) }
            }
            .padding(.horizontal, 20).padding(.top, 24).padding(.bottom, 24)
        }
    }
}

/// The deadline has passed: extend, mark complete or archive. Whatever happened, no guilt.
struct RecapCard: View {
    let goal: GoalState
    var onComplete: () -> Void
    var onExtend: () -> Void
    var onArchive: () -> Void
    var body: some View {
        let n = goal.progress.activeDays
        VStack(alignment: .leading, spacing: 10) {
            Text("TARGET DATE · \(Day.parse(goal.deadline)!.formatted(.dateTime.month(.abbreviated).day()).uppercased())").bridgeLabel(10, color: Theme.accInk)
            Text("You reached your date.").font(BFont.display(20)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
            Text("You showed up on ^[\(n) day](inflect: true) along the way. Where should this goal go next?").font(BFont.body(14)).foregroundStyle(Theme.ink2)
            Spacer().frame(height: 4)
            Button("I made it: mark complete", action: onComplete).buttonStyle(PrimaryButtonStyle())
            Button("Give it more time", action: onExtend).buttonStyle(SecondaryButtonStyle())
            Button("Archive it", action: onArchive).font(BFont.body(14, .medium)).foregroundStyle(Theme.mut).frame(maxWidth: .infinity, minHeight: 48)
        }
        .padding(18).frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.s1, in: RoundedRectangle(cornerRadius: 20, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(Theme.accLine, lineWidth: 1))
    }
}

/// Three or more days missed: pick up with today's plan only.
struct AwayCard: View {
    var onAdjust: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Welcome back.").font(BFont.display(20)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
            Text("Here's today's plan. Nothing to catch up on.").font(BFont.body(14)).foregroundStyle(Theme.ink2)
            Button(action: onAdjust) {
                HStack(spacing: 6) { Text("Adjust the plan in Reality Check"); Image(systemName: "arrow.right").font(.system(size: 14)) }
                    .font(BFont.body(14, .medium)).foregroundStyle(Theme.accInk).frame(minHeight: 44)
            }
        }
        .padding(.horizontal, 18).padding(.top, 18).padding(.bottom, 8).frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.s1, in: RoundedRectangle(cornerRadius: 20, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(Theme.line, lineWidth: 1))
    }
}

struct ArchiveGoalSheet: View {
    var onArchive: () -> Void
    var onCancel: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Archive this goal?").font(BFont.display(20)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
            Text("Its tasks leave your daily plan. Images, journal entries and history are kept.").font(BFont.body(15)).foregroundStyle(Theme.ink2)
            Button("Archive", action: onArchive).font(BFont.body(15, .semibold)).foregroundStyle(Theme.ink).frame(maxWidth: .infinity, minHeight: 56).background(Theme.bad, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            Button("Cancel", action: onCancel).buttonStyle(SecondaryButtonStyle())
            Spacer()
        }
        .padding(.horizontal, 20).padding(.top, 28)
        .presentationDetents([.medium])
    }
}

/// The goal was marked complete: the future self, the days it took, and the next goal.
struct GoalCrossedView: View {
    let goal: GoalState
    var onNext: () -> Void
    var onClose: () -> Void
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ZStack(alignment: .topLeading) {
                    RemoteImage(url: goal.images["future"]?.url).frame(height: 470).clipped()
                    LinearGradient(stops: [.init(color: .clear, location: 0.55), .init(color: Theme.bg, location: 1)], startPoint: .top, endPoint: .bottom)
                    Text("FUTURE SELF").bridgeLabel(10, color: Theme.accInk).padding(.leading, 24).padding(.top, 64)
                }
                .frame(height: 470)
                VStack(alignment: .leading, spacing: 14) {
                    Text("\(goal.label.uppercased()) · GOAL CROSSED").bridgeLabel(11, color: Theme.ok)
                    Text("You crossed the bridge.").font(BFont.display(34)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                    Text(goal.statement).font(BFont.body(15, .medium)).foregroundStyle(Theme.ink2)
                    Text("^[\(goal.progress.activeDays) day](inflect: true) of showing up got you here. Your Hall of Fame has a new badge.").font(BFont.body(15)).foregroundStyle(Theme.mut)
                    Spacer(minLength: 20)
                    Button("Set your next goal", action: onNext).buttonStyle(PrimaryButtonStyle())
                    Button("Back to Today", action: onClose).font(BFont.body(15, .medium)).foregroundStyle(Theme.ink2).frame(maxWidth: .infinity, minHeight: 48)
                }
                .padding(.horizontal, 24).padding(.bottom, 40).offset(y: -90).padding(.bottom, -90)
            }
        }
        .ignoresSafeArea(edges: .top)
        .background(Theme.bg)
    }
}

/// Full-screen moment when the last task of the day is ticked.
struct CelebrateView: View {
    let goal: GoalState
    let next: GoalState?
    var onNext: () -> Void
    var onJournal: () -> Void
    var onClose: () -> Void
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ZStack(alignment: .topLeading) {
                    RemoteImage(url: goal.images["future"]?.url).frame(height: 470).clipped()
                    LinearGradient(stops: [.init(color: .clear, location: 0.55), .init(color: Theme.bg, location: 1)], startPoint: .top, endPoint: .bottom)
                    Text("FUTURE SELF").bridgeLabel(10, color: Theme.accInk).padding(.leading, 24).padding(.top, 64)
                }
                .frame(height: 470)
                VStack(alignment: .leading, spacing: 14) {
                    Text("\(goal.label.uppercased()) · DAY \(goal.progress.day) COMPLETE").bridgeLabel(11, color: Theme.ok)
                    Text("You crossed today's span.").font(BFont.display(34)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                    HStack(alignment: .lastTextBaseline, spacing: 10) {
                        Text("\(goal.progress.streak)").font(.custom("Bricolage Grotesque", size: 64).weight(.bold)).foregroundStyle(Theme.accInk)
                        Text("day streak").font(BFont.body(15, .medium)).foregroundStyle(Theme.ink2)
                    }
                    Text("\(max(0, goal.progress.total - goal.progress.day)) days to the other side. Take two minutes to write down what worked today.").font(BFont.body(15)).foregroundStyle(Theme.mut)
                    Spacer(minLength: 20)
                    if let next {
                        Button(action: onNext) {
                            HStack { Text("Next: \(next.label) · \(next.progress.tasksToday - next.progress.doneToday) left"); Spacer(); Image(systemName: "arrow.right") }
                                .font(BFont.body(15, .medium)).foregroundStyle(Theme.ink).padding(.horizontal, 18).frame(minHeight: 52)
                                .background(Theme.s1, in: RoundedRectangle(cornerRadius: 16, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Theme.line, lineWidth: 1))
                        }
                    }
                    Button("Reflect in Journal", action: onJournal).buttonStyle(PrimaryButtonStyle())
                    Button("Back to today", action: onClose).font(BFont.body(15, .medium)).foregroundStyle(Theme.ink2).frame(maxWidth: .infinity, minHeight: 48)
                }
                .padding(.horizontal, 24).padding(.bottom, 40).offset(y: -90).padding(.bottom, -90)
            }
        }
        .ignoresSafeArea(edges: .top)
        .background(Theme.bg)
    }
}
