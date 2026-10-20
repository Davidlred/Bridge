import SwiftData
import SwiftUI

/// The coach conversation and Study Mode. Messages live in the local store; a send streams the reply into a
/// message as tokens arrive. The model sees the goals, today's tasks, the streak and the last 20 messages.
@MainActor @Observable
final class CoachModel {
    private(set) var messages: [ChatMessageEntity] = []
    var input = ""
    var awaiting = false
    var streaming = false
    var happyUntil = Date.distantPast
    var notice: String?
    var showEarlier = false
    // Study Mode
    var studySeconds = 0
    var studyNotes = ""
    var studyInput = ""
    var studyReplyId: UUID?
    var studyPaused = false
    @ObservationIgnored private var timer: Task<Void, Never>?
    @ObservationIgnored private var background: Task<Void, Never>?
    @ObservationIgnored private var ctx: ModelContext { Persistence.container.mainContext }

    /// The current conversation. On a fresh device it continues the newest conversation pulled from the account.
    var conversation: UUID {
        if let s = UserDefaults.standard.string(forKey: "coachConversation"), let id = UUID(uuidString: s) { return id }
        let id = messages.last?.conversation ?? UUIDv7.next(); UserDefaults.standard.set(id.uuidString, forKey: "coachConversation"); return id
    }
    var busy: Bool { awaiting || streaming }

    init() {
        reload()
        NotificationCenter.default.addObserver(forName: .syncApplied, object: nil, queue: .main) { [weak self] _ in MainActor.assumeIsolated { self?.reload() } }
    }
    func reload() { messages = (try? ctx.fetch(FetchDescriptor<ChatMessageEntity>(sortBy: [SortDescriptor(\.createdAt)]))) ?? [] }

    var shown: [ChatMessageEntity] { showEarlier ? messages : messages.filter { $0.conversation == conversation } }
    var hasEarlier: Bool { !showEarlier && messages.contains { $0.conversation != conversation } }

    /// typing → listening, awaiting → thinking, streaming → talking, card added → happy for 1.3 s.
    func mood(now: Date, typing: Bool) -> MascotMood {
        if awaiting { return .thinking }
        if streaming { return .talking }
        if now < happyUntil { return .happy }
        return typing ? .listening : .idle
    }

    func newConversation() { showEarlier = false; UserDefaults.standard.set(UUIDv7.next().uuidString, forKey: "coachConversation"); reload() }

    static func suggestion(_ m: ChatMessageEntity) -> Suggestion? { m.suggestionJSON.flatMap { try? JSONDecoder().decode(Suggestion.self, from: $0) } }

    func send(store: GoalStore, focus: GoalState?, study: Bool = false, retry: ChatMessageEntity? = nil) {
        let text = (retry?.text ?? (study ? studyInput : input)).trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty, !busy else { return }
        if study { studyInput = ""; studyReplyId = nil } else if retry == nil { input = "" }
        if retry == nil { Analytics.track(.coachMessageSent, ["study": study]) }
        let conv = conversation
        let history = shown.filter { $0.status != "failed" && !$0.text.isEmpty && $0.id != retry?.id }.suffix(20).map { ["role": $0.role, "text": String($0.text.prefix(4000))] }
        let user = retry ?? ChatMessageEntity(conversation: conv, role: "user", text: text, status: "sending")
        if retry == nil { ctx.insert(user) } else { user.status = "sending" }
        try? ctx.save(); reload()
        let goals: [[String: Any]] = store.goals.prefix(3).map { g in
            ["id": g.id.uuidString.lowercased(), "label": g.label, "statement": g.statement, "deadline": g.deadline, "day": g.progress.day, "total": g.progress.total, "streak": g.progress.streak,
             "tasks": g.tasks.prefix(20).map { t -> [String: Any] in ["title": t.title, "done": t.done, "priority": t.priority, "timeOfDay": t.timeOfDay as Any? ?? NSNull()] }]
        }
        let reply = ChatMessageEntity(conversation: conv, role: "coach", text: "", status: "streaming", createdAt: .now.addingTimeInterval(0.001))
        // Ids let the server store this exchange so it follows the account to other devices.
        let body: [String: Any] = ["message": text, "mode": study ? "study" : "chat", "localDate": LocalDay.today(), "history": history, "goals": goals,
                                   "focusGoalId": focus?.id.uuidString.lowercased() ?? NSNull(),
                                   "conversation": conv.uuidString.lowercased(), "messageId": user.id.uuidString.lowercased(), "replyId": reply.id.uuidString.lowercased(),
                                   "sentAt": Int(user.createdAt.timeIntervalSince1970 * 1000)]
        awaiting = true; notice = nil
        let payload = (try? JSONSerialization.data(withJSONObject: body)) ?? Data()
        let replyId = reply.id, userId = user.id
        pendingReply = reply
        Task {
            let stream = StreamState()
            do {
                try await APIClient.shared.coach(payload) { [weak self] event in self?.handle(event, replyId: replyId, userId: userId, stream: stream) }
                reply.status = "done"
            } catch CoachStreamError.interrupted {
                // Keep the partial reply and say it was interrupted; the user can retry.
                if stream.started { reply.status = "interrupted" } else { user.status = "failed" }
            } catch APIError.offline {
                user.status = "failed"; notice = String(localized: "You're offline. We'll keep the message here.")
            } catch APIError.server(_, let code, _, _) where code == "ai_quota" {
                user.status = "failed"; notice = String(localized: "You've reached today's coach messages. They reset tomorrow.")
            } catch { user.status = "failed" }
            awaiting = false; streaming = false; pendingReply = nil
            try? ctx.save(); reload()
            if study && stream.started { studyReplyId = reply.id }
        }
    }

    @ObservationIgnored private var pendingReply: ChatMessageEntity?

    /// Applies one stream event. Runs on the main actor; models are looked up by id.
    private func handle(_ event: CoachEvent, replyId: UUID, userId: UUID, stream: StreamState) {
        guard let reply = pendingReply, reply.id == replyId else { return }
        switch event {
        case .token(let t):
            if !stream.started {
                stream.started = true
                messages.first { $0.id == userId }?.status = "sent"
                ctx.insert(reply); awaiting = false; streaming = true
            }
            reply.text += t
        case .suggestion(let s):
            reply.suggestionJSON = try? JSONEncoder().encode(s); reply.suggestionState = "pending"
        }
        reload()
    }

    /// Retry after "Reply interrupted": drop the partial reply and ask the question again.
    func retryInterrupted(_ reply: ChatMessageEntity, store: GoalStore, focus: GoalState?) {
        guard let question = messages.last(where: { $0.role == "user" && $0.createdAt < reply.createdAt }) else { return }
        store.syncChat(reply.id, op: "delete")
        ctx.delete(reply); try? ctx.save(); reload()
        send(store: store, focus: focus, retry: question)
    }

    func add(_ m: ChatMessageEntity, store: GoalStore, focus: GoalState?) {
        guard let s = Self.suggestion(m), let focus else { return }
        store.addSuggestedTask(goalId: focus.id, s)
        m.suggestionState = "added"; try? ctx.save(); reload()
        store.syncChat(m.id, op: "upsert", patch: ["suggestionState": "added"])
        happyUntil = .now.addingTimeInterval(1.3)
    }
    func dismiss(_ m: ChatMessageEntity, store: GoalStore) { m.suggestionState = "dismissed"; try? ctx.save(); reload(); store.syncChat(m.id, op: "upsert", patch: ["suggestionState": "dismissed"]) }

    // Study Mode timer: pauses 10 s after the app goes to the background.
    func enterStudy() { studySeconds = 0; studyReplyId = nil; studyPaused = false; startTimer() }
    private func startTimer() {
        timer?.cancel()
        timer = Task { while !Task.isCancelled { try? await Task.sleep(for: .seconds(1)); if !Task.isCancelled { studySeconds += 1 } } }
    }
    func exitStudy() { timer?.cancel(); background?.cancel() }
    func backgrounded() { background = Task { try? await Task.sleep(for: .seconds(10)); if !Task.isCancelled { timer?.cancel(); studyPaused = true } } }
    func foregrounded() { background?.cancel() }
    func resume() { studyPaused = false; startTimer() }
}

@MainActor final class StreamState { var started = false }

private struct StatusLine: View {
    let mood: MascotMood
    var body: some View {
        let (text, dot): (LocalizedStringKey, Color) = switch mood {
            case .idle: ("Here when you need me", Theme.mut2)
            case .listening: ("Listening…", Theme.acc)
            case .thinking: ("Thinking it through…", Theme.warn)
            case .talking: ("Talking", Theme.ok)
            case .happy: ("Added. Nice one.", Theme.ok)
        }
        HStack(spacing: 6) { Circle().fill(dot).frame(width: 6, height: 6); Text(text).font(BFont.body(12, .medium)).foregroundStyle(Theme.mut) }
            .accessibilityElement(children: .combine)
    }
}

struct CoachView: View {
    @Environment(SessionStore.self) private var session
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(GoalStore.self) private var store
    @Environment(AppRouter.self) private var router
    @Environment(CoachModel.self) private var model
    @FocusState private var focused: Bool

    private var headerButtons: some View {
        HStack(spacing: 6) {
            Button { model.enterStudy(); router.coach.append(Route.study) } label: {
                Label("Study", systemImage: "viewfinder").font(BFont.body(13, .medium)).foregroundStyle(Theme.ink).padding(.horizontal, 14).frame(minHeight: 44)
                    .background(Theme.s1, in: Capsule()).overlay(Capsule().stroke(Theme.line, lineWidth: 1))
            }
            // Fixed size: the icon has to stay inside its 44pt circle at every text size.
            Button(action: model.newConversation) { Image(systemName: "plus.bubble").font(.system(size: 17, weight: .medium)).foregroundStyle(Theme.ink).frame(width: 44, height: 44).background(Theme.s1, in: Circle()).overlay(Circle().stroke(Theme.line, lineWidth: 1)) }
                .accessibilityLabel("New conversation")
        }
    }

    var body: some View {
        @Bindable var model = model
        let focus = store.goals.first
        let name = session.user?.coachName ?? "Arc"
        TimelineView(.periodic(from: .now, by: 0.2)) { tl in
            let mood = model.mood(now: tl.date, typing: focused && !model.input.isEmpty)
            VStack(spacing: 0) {
                VStack(spacing: 4) {
                    // Accessibility text sizes: the buttons get their own row instead of sitting over the mascot.
                    if typeSize.isAccessibilitySize { headerButtons.frame(maxWidth: .infinity, alignment: .trailing) }
                    CoachMascot(mood: mood).padding(.top, 6).accessibilityLabel("\(name), your coach")
                    Text(name).font(BFont.display(21)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                    StatusLine(mood: mood)
                    if let f = focus {
                        Text(store.goals.count > 1 ? "\(store.goals.count) GOALS IN CONTEXT · FOCUS: \(f.label.uppercased())" : "KNOWS YOUR GOAL · DAY \(f.progress.day)").bridgeLabel(9.5, color: Theme.mut2)
                    }
                }
                .frame(maxWidth: .infinity).padding(.horizontal, 16).padding(.top, 6).padding(.bottom, 14)
                .overlay(alignment: .topTrailing) {
                    if !typeSize.isAccessibilitySize { headerButtons.padding(.trailing, 16).padding(.top, 4) }
                }
                Divider().overlay(Theme.line)
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 12) {
                            if model.hasEarlier {
                                Button("Show earlier conversation") { model.showEarlier = true }.font(BFont.body(12, .medium)).foregroundStyle(Theme.mut).padding(.horizontal, 14).frame(minHeight: 36)
                                    .overlay(Capsule().stroke(Theme.line, lineWidth: 1)).frame(maxWidth: .infinity)
                            }
                            if model.shown.isEmpty { CoachBubbleText(text: store.goals.isEmpty ? String(localized: "Once you have a goal, ask anything about your plan.") : String(localized: "I've read your goal and your routine. Week one is deliberately light so it sticks. Ask me anything, or tell me what's getting in the way.")) }
                            ForEach(Array(model.shown.enumerated()), id: \.element.id) { i, m in
                                if i > 0 && model.shown[i - 1].conversation != m.conversation {
                                    HStack(spacing: 10) { Rectangle().fill(Theme.track).frame(height: 1); Text("NEW CONVERSATION").bridgeLabel(10, color: Theme.mut2).fixedSize(); Rectangle().fill(Theme.track).frame(height: 1) }
                                }
                                if m.role == "coach" { coachMessage(m, focus: focus) } else { userMessage(m, focus: focus) }
                            }
                            if model.awaiting { TypingDots() }
                            Color.clear.frame(height: 1).id("end")
                        }
                        .padding(.horizontal, 16).padding(.top, 16).padding(.bottom, 12)
                    }
                    .onChange(of: model.messages.last?.text) { proxy.scrollTo("end", anchor: .bottom) }
                    .onChange(of: model.awaiting) { proxy.scrollTo("end", anchor: .bottom) }
                }
                if let n = model.notice { Text(n).font(BFont.body(13)).foregroundStyle(Theme.warn).padding(.horizontal, 16).padding(.vertical, 6) }
                if !model.busy && model.input.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(["I want to start meditating", "I missed yesterday", "Make tomorrow easier"], id: \.self) { chip in
                                Button { model.input = String(localized: String.LocalizationValue(chip)) } label: {
                                    Text(LocalizedStringKey(chip)).font(BFont.body(13, .medium)).foregroundStyle(Theme.accInk).padding(.horizontal, 14).frame(minHeight: 36).overlay(Capsule().stroke(Theme.accLine, lineWidth: 1))
                                }
                            }
                        }
                        .padding(.horizontal, 16)
                    }
                    .padding(.bottom, 10)
                }
                HStack(spacing: 8) {
                    TextField("Message your coach", text: $model.input).focused($focused).font(BFont.body(15)).submitLabel(.send).onSubmit { model.send(store: store, focus: focus) }
                        .padding(.horizontal, 18).frame(minHeight: 48).background(Theme.s1, in: Capsule()).overlay(Capsule().stroke(focused ? Theme.acc : Theme.line, lineWidth: 1))
                    SendButton(enabled: !model.input.trimmingCharacters(in: .whitespaces).isEmpty && !model.busy, size: 48) { model.send(store: store, focus: focus) }
                }
                .padding(.horizontal, 16).padding(.bottom, 12)
            }
        }
        .background(Theme.bg)
        .toolbar(.hidden, for: .navigationBar)
        .onAppear { model.reload() }
    }

    @ViewBuilder private func coachMessage(_ m: ChatMessageEntity, focus: GoalState?) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            if !m.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { CoachBubbleText(text: m.text.trimmingCharacters(in: .whitespacesAndNewlines)) }
            if m.status == "interrupted" {
                Button("Reply interrupted · Retry") { model.retryInterrupted(m, store: store, focus: focus) }.font(BFont.body(12, .medium)).foregroundStyle(Theme.bad).frame(minHeight: 32)
            }
            // The card appears only after the stream finishes.
            if let s = CoachModel.suggestion(m), m.status == "done" {
                switch m.suggestionState {
                case "added":
                    Label("Added: \(s.title)", systemImage: "checkmark.circle.fill").font(BFont.body(13, .medium)).foregroundStyle(Theme.ok).padding(.horizontal, 12).padding(.vertical, 10).frame(maxWidth: .infinity, alignment: .leading)
                        .background(Theme.okTint, in: RoundedRectangle(cornerRadius: 12, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(Theme.ok, lineWidth: 1))
                case "dismissed":
                    Text("Suggestion dismissed").font(BFont.body(12)).foregroundStyle(Theme.mut2).padding(.leading, 4)
                default:
                    VStack(alignment: .leading, spacing: 8) {
                        Text("ADD TO PLAN").bridgeLabel(10, color: Theme.acc)
                        Text(s.title).font(BFont.display(15)).foregroundStyle(Theme.ink)
                        if !s.rationale.isEmpty { Text(s.rationale).font(BFont.body(13)).foregroundStyle(Theme.mut) }
                        HStack(spacing: 8) {
                            Button { model.add(m, store: store, focus: focus) } label: {
                                Text("Add to \(focus?.label ?? "")").font(BFont.body(14, .semibold)).lineLimit(1).foregroundStyle(Theme.onAcc).frame(maxWidth: .infinity, minHeight: 44).background(Theme.acc, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                            }
                            .disabled(focus == nil)
                            Button("Dismiss") { model.dismiss(m, store: store) }.font(BFont.body(14, .medium)).foregroundStyle(Theme.ink2).padding(.horizontal, 16).frame(minHeight: 44).overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(Theme.line, lineWidth: 1))
                        }
                        .padding(.top, 4)
                    }
                    .padding(14).background(Theme.accTint, in: RoundedRectangle(cornerRadius: 16, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Theme.accLine, lineWidth: 1))
                }
            }
        }
        .frame(maxWidth: 320, alignment: .leading)
    }

    @ViewBuilder private func userMessage(_ m: ChatMessageEntity, focus: GoalState?) -> some View {
        VStack(alignment: .trailing, spacing: 6) {
            Text(m.text).font(BFont.body(15)).lineSpacing(3).foregroundStyle(Theme.ink).padding(.horizontal, 14).padding(.vertical, 12)
                .background(Theme.bubble, in: UnevenRoundedRectangle(topLeadingRadius: 18, bottomLeadingRadius: 18, bottomTrailingRadius: 6, topTrailingRadius: 18, style: .continuous))
            if m.status == "failed" {
                Button { model.send(store: store, focus: focus, retry: m) } label: { Label("Not sent · Tap to retry", systemImage: "exclamationmark.circle").font(BFont.body(12, .medium)).foregroundStyle(Theme.bad) }.frame(minHeight: 32)
            }
        }
        .frame(maxWidth: .infinity, alignment: .trailing).padding(.leading, 60)
    }
}

private struct CoachBubbleText: View {
    let text: String
    var body: some View {
        Text(text).font(BFont.body(15)).lineSpacing(3).foregroundStyle(Theme.ink).padding(.horizontal, 14).padding(.vertical, 12)
            .background(Theme.s1, in: UnevenRoundedRectangle(topLeadingRadius: 18, bottomLeadingRadius: 6, bottomTrailingRadius: 18, topTrailingRadius: 18, style: .continuous))
            .overlay(UnevenRoundedRectangle(topLeadingRadius: 18, bottomLeadingRadius: 6, bottomTrailingRadius: 18, topTrailingRadius: 18, style: .continuous).stroke(Theme.line, lineWidth: 1))
            .frame(maxWidth: 320, alignment: .leading)
    }
}

private struct SendButton: View {
    let enabled: Bool, size: CGFloat
    var action: () -> Void
    var body: some View {
        Button(action: action) { Image(systemName: "arrow.up").font(.system(size: 18, weight: .semibold)).foregroundStyle(Theme.onAcc).frame(width: size, height: size).background(Theme.acc, in: Circle()) }
            .disabled(!enabled).opacity(enabled ? 1 : 0.35).accessibilityLabel("Send")
    }
}

private struct TypingDots: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var on = false
    var body: some View {
        HStack(spacing: 5) {
            ForEach(0..<3, id: \.self) { i in
                Circle().fill(Theme.mut).frame(width: 7, height: 7).offset(y: on ? -4 : 0).opacity(on ? 1 : 0.4)
                    .animation(reduceMotion ? nil : .easeInOut(duration: 0.6).repeatForever().delay(Double(i) * 0.15), value: on)
            }
        }
        .padding(16).background(Theme.s1, in: UnevenRoundedRectangle(topLeadingRadius: 18, bottomLeadingRadius: 6, bottomTrailingRadius: 18, topTrailingRadius: 18, style: .continuous))
        .onAppear { on = true }
        .accessibilityLabel("Coach is typing")
    }
}

/// Full-screen focus timer with notes and a mini coach strip at the bottom.
struct StudyModeView: View {
    @Environment(GoalStore.self) private var store
    @Environment(AppRouter.self) private var router
    @Environment(CoachModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var pulse = false

    var body: some View {
        @Bindable var model = model
        let focus = store.goals.first
        ZStack(alignment: .bottom) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 10) {
                    Circle().fill(Theme.acc).frame(width: 7, height: 7).opacity(pulse ? 1 : 0.35)
                        .animation(reduceMotion ? nil : .easeInOut(duration: 0.7).repeatForever(), value: pulse).onAppear { pulse = true }
                    Text("STUDY MODE").bridgeLabel(11)
                    Text(String(format: "%02d:%02d", model.studySeconds / 60, model.studySeconds % 60)).font(BFont.label(14)).foregroundStyle(Theme.ink).monospacedDigit()
                    Spacer()
                    Button { model.exitStudy(); router.coach.removeLast() } label: {
                        Label("Exit", systemImage: "arrow.down.right.and.arrow.up.left").font(BFont.body(13, .medium)).foregroundStyle(Theme.ink).padding(.horizontal, 16).frame(minHeight: 44).overlay(Capsule().stroke(Theme.line, lineWidth: 1))
                    }
                }
                .padding(.leading, 22).padding(.trailing, 16).padding(.vertical, 6)
                if let f = focus { Text("Working toward: \(f.statement)").font(BFont.body(12)).foregroundStyle(Theme.mut2).lineLimit(1).padding(.horizontal, 22) }
                TextField("Start writing, or just think. Your coach is one tap away below.", text: $model.studyNotes, axis: .vertical)
                    .font(BFont.body(18)).lineSpacing(8).foregroundStyle(Theme.ink).padding(.horizontal, 22).padding(.top, 22)
                Spacer(minLength: 150)
            }
            VStack(spacing: 12) {
                if let id = model.studyReplyId, let reply = model.messages.first(where: { $0.id == id }) ?? (model.streaming ? model.messages.last : nil) {
                    VStack(alignment: .leading, spacing: 10) {
                        HStack {
                            Text("COACH").bridgeLabel(10, color: Theme.acc)
                            Spacer()
                            Button { model.studyReplyId = nil } label: { Image(systemName: "xmark").foregroundStyle(Theme.mut).frame(width: 32, height: 32) }.accessibilityLabel("Close")
                        }
                        ScrollView { Text(reply.text).font(BFont.body(14)).foregroundStyle(Theme.ink).frame(maxWidth: .infinity, alignment: .leading) }.frame(maxHeight: 120)
                        if let s = CoachModel.suggestion(reply), reply.status == "done" {
                            if reply.suggestionState == "added" { Text("Added to today's plan").font(BFont.body(12, .medium)).foregroundStyle(Theme.ok) }
                            else if reply.suggestionState == "pending" {
                                Button { model.add(reply, store: store, focus: focus) } label: {
                                    Label("Add \"\(s.title)\"", systemImage: "plus").font(BFont.body(13, .semibold)).lineLimit(1).foregroundStyle(Theme.accInk).frame(maxWidth: .infinity, minHeight: 44)
                                        .background(Theme.accTint, in: RoundedRectangle(cornerRadius: 12, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(Theme.accLine, lineWidth: 1))
                                }
                            }
                        }
                    }
                    .padding(14).background(Theme.s1, in: RoundedRectangle(cornerRadius: 18, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Theme.line, lineWidth: 1))
                }
                HStack(spacing: 8) {
                    TimelineView(.periodic(from: .now, by: 0.2)) { tl in CoachMascot(mood: model.mood(now: tl.date, typing: !model.studyInput.isEmpty), size: 34) }
                    TextField("Ask your coach…", text: $model.studyInput).font(BFont.body(15)).submitLabel(.send).onSubmit { model.send(store: store, focus: focus, study: true) }
                    SendButton(enabled: !model.studyInput.trimmingCharacters(in: .whitespaces).isEmpty && !model.busy, size: 44) { model.send(store: store, focus: focus, study: true) }
                }
                .padding(.leading, 10).padding(.trailing, 6).frame(minHeight: 58).background(Theme.s1, in: Capsule()).overlay(Capsule().stroke(Theme.line, lineWidth: 1))
            }
            .padding(.horizontal, 16).padding(.bottom, 30)
            if model.studyPaused {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Timer paused").font(BFont.display(20)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                    Text("You left the app for a while, so we paused your session.").font(BFont.body(14)).foregroundStyle(Theme.mut)
                    Button("Resume", action: model.resume).buttonStyle(PrimaryButtonStyle())
                }
                .padding(20).background(Theme.s1, in: RoundedRectangle(cornerRadius: 20, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(Theme.line, lineWidth: 1))
                .padding(24).frame(maxHeight: .infinity)
            }
        }
        .background(Theme.s0)
        .toolbar(.hidden, for: .navigationBar)
        .onChange(of: scenePhase) { _, p in if p == .background { model.backgrounded() } else if p == .active { model.foregrounded() } }
    }
}
