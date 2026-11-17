import SwiftUI

/// Whose Day? state on this device. The server runs the game; this sends intents and shows what the server says.
@MainActor @Observable
final class WhoseDayModel {
    var me: String?
    var game: WhoseDayDTO?
    /// Told only to this player by the server.
    var you: WdYouDTO?
    var choice: String?
    var nextTapped = -1
    var notice: String?
    var connected = true
    /// How far the server's clock is ahead of this device's (ms). Stages and timers follow server time.
    var offset: Double = 0

    var isOwner: Bool { you?.owner == true && you?.round == game?.current }
    var myGuess: String? { you?.round == game?.current ? you?.guess?.guessId : nil }
    func name(_ id: String?) -> String { game?.players.first { $0.userId == id }?.name ?? "" }
    func serverNow(_ now: Date) -> Double { now.timeIntervalSince1970 * 1000 + offset }
    func secondsTo(_ at: Double, _ now: Date) -> Int { max(0, Int(((at - serverNow(now)) / 1000).rounded(.up))) }
}

struct WhoseDayScreen: View {
    let allianceId: String
    @Environment(SocialStore.self) private var social
    @Environment(SessionStore.self) private var session
    @Environment(Toaster.self) private var toaster
    @Environment(\.dismiss) private var dismiss
    @State private var model = WhoseDayModel()

    var body: some View {
        WhoseDayContent(model: model, actions: .init(
            exit: { frame("wd:leave"); dismiss() },
            start: { frame("wd:start") },
            pool: { on in model.you?.pool = on; frame("wd:pool", ["on": on]) },
            choose: { if model.myGuess == nil { model.choice = $0 } },
            lock: { if let t = model.choice { frame("wd:guess", ["target": t]) } },
            next: { if let r = model.game?.current { model.nextTapped = r; frame("wd:next", ["round": r]) } },
            share: share,
            rematch: { Task { await open() } }
        ))
        .toolbar(.hidden, for: .navigationBar)
        .task {
            model.me = session.user?.id.uuidString.lowercased()
            let rt = social.realtime!
            rt.gameListener = { handle($0) }
            rt.onReady = { model.connected = true; frame("wd:join") }        // first connect and every reconnect resume from server state
            rt.acquire()
            await open()
        }
        .onDisappear { frame("wd:leave"); social.realtime.gameListener = nil; social.realtime.onReady = nil; social.realtime.release() }
    }

    private func open() async {
        do {
            let g = try await social.openWhoseDay(allianceId)
            let me = model.me
            model = WhoseDayModel(); model.me = me; model.game = g; model.offset = g.now - Date().timeIntervalSince1970 * 1000
            frame("wd:join")
        } catch APIError.server(_, "other_game_open", _, _) {
            model.notice = String(localized: "Who's Drifting? is already running. Join that one from the card.")
        } catch {
            model.connected = false
            toaster.show(String(localized: "Game Night needs a connection."), .warn)
        }
    }

    private func frame(_ type: String, _ extra: [String: Any] = [:]) {
        guard let id = model.game?.id else { return }
        social.realtime.send(extra.merging(["type": type, "gameId": id]) { a, _ in a })
    }

    private func handle(_ e: [String: Any]) {
        switch e["type"] as? String {
        case "wd:state":
            guard let raw = e["game"], let data = try? JSONSerialization.data(withJSONObject: raw), let g = try? JSONDecoder().decode(WhoseDayDTO.self, from: data), g.id == model.game?.id else { return }
            if g.current != model.game?.current || (g.phase == "round" && model.game?.phase != "round") { model.choice = nil }
            model.game = g; model.offset = g.now - Date().timeIntervalSince1970 * 1000; model.notice = nil
        case "wd:you":
            guard let data = try? JSONSerialization.data(withJSONObject: e), let y = try? JSONDecoder().decode(WdYouDTO.self, from: data), y.gameId == model.game?.id else { return }
            model.you = y
        case "wd:error":
            switch e["error"] as? String {
            case "game_over": model.notice = String(localized: "That game has ended.")
            case "already_started": model.notice = String(localized: "Game in progress · join the next one")
            case "need_players": toaster.show(String(localized: "Need 3 players"), .warn)
            case "no_lists": toaster.show(String(localized: "Nobody's list can be shown yet. Someone needs to finish today with their list in the pool."), .warn)
            default: break
            }
        default: break
        }
    }

    private func share() {
        guard let id = model.game?.id else { return }
        Task {
            do { try await social.shareGame(allianceId, gameId: id); toaster.show(String(localized: "Shared to alliance chat."), .ok) }
            catch { toaster.show(String(localized: "You're offline. Try again when you reconnect."), .warn) }
        }
    }
}

struct WdActions {
    var exit: () -> Void = {}, start: () -> Void = {}, pool: (Bool) -> Void = { _ in }, choose: (String) -> Void = { _ in }, lock: () -> Void = {}
    var next: () -> Void = {}, share: () -> Void = {}, rematch: () -> Void = {}
}

struct WhoseDayContent: View {
    let model: WhoseDayModel
    var actions = WdActions()
    var now: Date?

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button(action: actions.exit) { Image(systemName: "xmark").font(.system(size: 20)).foregroundStyle(Theme.ink).frame(width: 44, height: 44) }.accessibilityLabel(Text("Leave game"))
                Text("GAME NIGHT · WHOSE DAY?").bridgeLabel(10, color: Theme.acc).frame(maxWidth: .infinity)
                Color.clear.frame(width: 44, height: 44)
            }
            .padding(.leading, 10).padding(.trailing, 16).padding(.vertical, 4)
            if !model.connected {
                Text("Reconnecting…").font(BFont.body(12, .medium)).foregroundStyle(Theme.warn).frame(maxWidth: .infinity).padding(8).background(Theme.warnTint)
            }
            TimelineView(.periodic(from: .now, by: 0.25)) { ctx in
                let t = now ?? ctx.date
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        if let n = model.notice { Text(n).font(BFont.body(14)).foregroundStyle(Theme.mut) }
                        if !model.connected && model.game == nil { Text("Game Night needs a connection.").font(BFont.body(14)).foregroundStyle(Theme.mut) }
                        if let g = model.game {
                            switch g.phase {
                            case "lobby": lobby(g)
                            case "round": if let r = g.round { round(g, r, t) }
                            case "reveal": if let r = g.round, let rv = r.reveal { reveal(g, r, rv, t) }
                            case "results": if let res = g.results { results(g, res) }
                            default:
                                Text("Nobody started the game, so the lobby closed.").font(BFont.body(15)).foregroundStyle(Theme.mut)
                                Button("Done", action: actions.exit).buttonStyle(SecondaryButtonStyle())
                            }
                        }
                    }
                    .padding(.horizontal, 20).padding(.top, 8).padding(.bottom, 36)
                }
            }
        }
        .background(Theme.s1)
    }

    // MARK: Lobby

    @ViewBuilder private func lobby(_ g: WhoseDayDTO) -> some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 8) {
                Text("Whose day was it?").font(BFont.display(30)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                Text("Guess who did each list. \(g.rounds) rounds, about 2 minutes.").font(BFont.body(15)).foregroundStyle(Theme.mut)
            }
            .padding(.top, 6).frame(maxWidth: .infinity, alignment: .leading)
            CoachMascot(mood: .talking, size: 92)
        }
        HStack(spacing: 12) {
            AvatarStack(names: g.players.map(\.name))
            if g.done.eligible > 0 { Text("✓ \(g.done.finished) OF \(g.done.eligible) DONE").bridgeLabel(10, color: Theme.ok) }
        }
        .accessibilityElement(children: .combine)
        VStack(alignment: .leading, spacing: 12) {
            Text("How to play").font(BFont.display(16)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
            ForEach(Array([String(localized: "Arc shows a finished list. No name on it."), String(localized: "A new clue every 5 seconds. Guess early for more points."),
                           String(localized: "Fool the group with your list and you score too.")].enumerated()), id: \.offset) { i, s in
                HStack(alignment: .top, spacing: 12) {
                    Text("\(i + 1)").font(BFont.label(11)).foregroundStyle(Theme.ink2).frame(width: 24, height: 24).overlay(Circle().strokeBorder(Theme.line2, lineWidth: 1))
                    Text(s).font(BFont.body(14)).foregroundStyle(Theme.ink2).padding(.top, 2).frame(maxWidth: .infinity, alignment: .leading)
                }
                .accessibilityElement(children: .combine)
            }
        }
        .padding(16).frame(maxWidth: .infinity, alignment: .leading).background(Theme.bg, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        poolCard
        let enough = g.players.filter { !$0.absent }.count >= 3
        Button(enough ? String(localized: "Start · \(g.rounds) rounds") : String(localized: "Need 3 players"), action: actions.start).buttonStyle(PrimaryButtonStyle()).disabled(!enough)
    }

    @ViewBuilder private var poolCard: some View {
        let on = model.you?.pool != false
        VStack(alignment: .leading, spacing: 0) {
            SwitchRow(on: Binding(get: { on }, set: { actions.pool($0) }), title: String(localized: "Your list in the pool"),
                      sub: on ? String(localized: "Others see your finished tasks and how long they took. Private tasks stay locked.") : String(localized: "Your list won't be shown. You can still play and guess."))
            VStack(alignment: .leading, spacing: 8) {
                if let y = model.you {
                    if y.eligible == false || y.preview.isEmpty {
                        Text("Finish today's tasks to put your list in.").font(BFont.body(13)).foregroundStyle(Theme.mut)
                    } else {
                        ForEach(Array(y.preview.enumerated()), id: \.offset) { _, t in
                            HStack(spacing: 8) {
                                Image(systemName: t.isPrivate ? "lock.fill" : "checkmark").font(.system(size: 12, weight: .semibold)).foregroundStyle(Theme.mut).frame(width: 16)
                                Text(t.name ?? String(localized: "Private task")).font(BFont.body(13)).foregroundStyle(t.isPrivate ? Theme.mut : Theme.ink2).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
                                if let m = t.durationMin { Text("\(m) min").font(BFont.label(11)).foregroundStyle(Theme.mut) }
                            }
                            .accessibilityElement(children: .combine)
                        }
                    }
                }
            }
            .padding(.horizontal, 14).padding(.vertical, 12).opacity(on ? 1 : 0.4)
        }
    }

    // MARK: Round

    @ViewBuilder private func round(_ g: WhoseDayDTO, _ r: WdRoundDTO, _ now: Date) -> some View {
        HStack {
            Text("Whose day was this?").font(BFont.display(22)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader).frame(maxWidth: .infinity, alignment: .leading)
            Text("ROUND \(r.index + 1) / \(r.total)").bridgeLabel(10)
        }
        // Time left to guess; once guessing closes, the countdown to the reveal.
        let closed = r.stage >= 4
        let end = closed ? r.revealAt : r.closesAt
        let span = closed ? r.revealAt - r.closesAt : r.closesAt - r.startsAt
        HStack(spacing: 12) {
            Text(String(format: "0:%02d", model.secondsTo(end, now))).font(BFont.label(20)).fontWeight(.semibold).foregroundStyle(Theme.ink)
            Bar(fraction: (end - model.serverNow(now)) / max(1, span), color: Theme.acc, height: 4)
        }
        mysteryList(r)
        clueLadder(r.stage, how: r.how)
        if closed { Text("Guessing closed. Revealing…").font(BFont.body(13)).foregroundStyle(Theme.mut) }
        if model.isOwner {
            VStack(spacing: 6) {
                Text("This is your list. Sit tight.").font(BFont.display(18)).foregroundStyle(Theme.ink).multilineTextAlignment(.center)
                Text("\(r.guessed) of \(r.guessers) guessed").bridgeLabel(11)
            }
            .padding(.vertical, 26).padding(.horizontal, 20).frame(maxWidth: .infinity).background(Theme.bg, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .accessibilityElement(children: .combine)
        } else {
            let picked = model.myGuess ?? model.choice
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 3), spacing: 8) {
                ForEach(Array(g.players.filter { $0.userId != model.me }.enumerated()), id: \.element.userId) { _, p in
                    let sel = picked == p.userId
                    Button { actions.choose(p.userId) } label: {
                        VStack(spacing: 6) {
                            Face(name: p.name, size: 40, index: g.players.firstIndex(of: p) ?? 0)
                            Text(p.name).font(BFont.body(13, .medium)).foregroundStyle(p.absent ? Theme.mut : Theme.ink).lineLimit(1)
                        }
                        .frame(maxWidth: .infinity, minHeight: 92)
                        .background(sel ? Theme.accTint : Theme.bg, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(sel ? Theme.ink2 : Theme.line, lineWidth: sel ? 2 : 1))
                    }
                    .buttonStyle(.plain).disabled(model.myGuess != nil || closed).accessibilityAddTraits(sel ? [.isSelected] : [])
                }
            }
            let pts = [3, 2, 1][safe: r.stage - 1] ?? 0
            if model.myGuess != nil {
                let waiting = max(0, r.guessers - r.guessed)
                Text(waiting > 0 ? String(localized: "Locked in · waiting for \(waiting) more") : String(localized: "Locked in")).font(BFont.body(14, .medium)).foregroundStyle(Theme.ink2).frame(maxWidth: .infinity)
            } else if !closed {
                if let c = model.choice {
                    Button(pts == 1 ? String(localized: "Lock in \(model.name(c)) · 1 pt") : String(localized: "Lock in \(model.name(c)) · \(pts) pts"), action: actions.lock).buttonStyle(PrimaryButtonStyle())
                } else {
                    Button("Pick a name") {}.buttonStyle(PrimaryButtonStyle()).disabled(true)
                }
            }
        }
    }

    private func mysteryList(_ r: WdRoundDTO) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            if r.how, let s = r.goalStreak { Text("\(s)-DAY STREAK ON THIS GOAL").bridgeLabel(10) }
            else if r.stage >= 2, let by = r.finishedBy {
                let at = clock(by).uppercased()
                Text((r.minutes ?? 0) > 0 ? "\(r.count) TASKS · \(r.minutes ?? 0) MIN · FINISHED BY \(at)" : "\(r.count) TASKS · FINISHED BY \(at)").bridgeLabel(10)
            }
            ForEach(Array(r.tasks.enumerated()), id: \.offset) { _, t in taskRow(t) }
            if let h = r.goalHint {
                Label { Text(r.how ? "Hint: \(h)" : "Goal hint: \(h)").font(BFont.body(13, .medium)).foregroundStyle(Theme.ink2) } icon: { Image(systemName: "flag.fill").foregroundStyle(Theme.ink2) }
                    .padding(.top, 4)
            }
        }
        .padding(14).frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.bg, in: RoundedRectangle(cornerRadius: 20, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(Theme.line, lineWidth: 1))
    }

    private func taskRow(_ t: WdTaskDTO) -> some View {
        let meta = [t.durationMin.map { String(localized: "\($0) min") }, t.doneAt.map(clock), t.streak.flatMap { $0 > 0 ? String(localized: "\($0) days running") : nil }].compactMap { $0 }.joined(separator: " · ")
        return HStack(spacing: 10) {
            Image(systemName: t.isPrivate ? "lock.fill" : "checkmark.circle.fill").font(.system(size: 18)).foregroundStyle(t.isPrivate ? Theme.mut : Theme.ok)
            VStack(alignment: .leading, spacing: 2) {
                Text(t.name ?? String(localized: "Private task")).font(BFont.body(14, .medium)).foregroundStyle(t.isPrivate ? Theme.mut : Theme.ink)
                if !meta.isEmpty { Text(meta).font(BFont.body(12)).foregroundStyle(Theme.mut) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if let p = t.priority {
                let pr: Theme.Priority = p == "high" ? .high : p == "low" ? .low : .medium
                Text(p == "high" ? "High" : p == "low" ? "Low" : "Med" as LocalizedStringKey).font(BFont.label(10)).foregroundStyle(pr.fg)
                    .padding(.horizontal, 8).padding(.vertical, 4).background(pr.bg, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
        .background(Theme.s1, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(t.isPrivate ? Theme.line2 : Theme.line, style: StrokeStyle(lineWidth: 1, dash: t.isPrivate ? [5, 4] : [])))
        .accessibilityElement(children: .combine)
    }

    /// Three clues: done ones muted with a check, the current one with a bolt, later ones locked. Stage changes are announced.
    private func clueLadder(_ stage: Int, how: Bool) -> some View {
        let labels = how ? [String(localized: "Order"), String(localized: "Streaks"), String(localized: "Routine")]
                         : [String(localized: "Tasks"), String(localized: "Time + priority"), String(localized: "Goal hint")]
        let points = [String(localized: "3 PTS"), String(localized: "2 PTS"), String(localized: "1 PT")]
        return HStack(spacing: 6) {
            ForEach(0..<3, id: \.self) { i in
                let n = i + 1
                let state = n < stage ? 0 : n == stage ? 1 : 2
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 4) {
                        Image(systemName: state == 0 ? "checkmark" : state == 1 ? "bolt.fill" : "lock.fill").font(.system(size: 10, weight: .semibold))
                        Text(points[i]).font(BFont.label(9))
                    }
                    .foregroundStyle(state == 1 ? Theme.ink : Theme.mut)
                    Text(labels[i]).font(BFont.body(12, .medium)).foregroundStyle(state == 1 ? Theme.ink : state == 0 ? Theme.mut : Theme.mut2).lineLimit(2)
                }
                .padding(.horizontal, 10).padding(.vertical, 8).frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
                .background(state == 1 ? Theme.accTint : .clear, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(state == 1 ? Theme.accLine : Theme.line, lineWidth: 1))
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Text(state == 0 ? "\(labels[i]), \(points[i]), shown" : state == 1 ? "\(labels[i]), \(points[i]), now" : "\(labels[i]), \(points[i]), not yet"))
            }
        }
        .onChange(of: stage) { _, s in
            let text = s == 2 ? (how ? String(localized: "Clue 2: streaks") : String(localized: "Clue 2: time and priority"))
                     : s == 3 ? (how ? String(localized: "Clue 3: a hint from their routine") : String(localized: "Clue 3: goal hint")) : nil
            if let text { AccessibilityNotification.Announcement(text).post() }
        }
    }

    // MARK: Reveal

    @ViewBuilder private func reveal(_ g: WhoseDayDTO, _ r: WdRoundDTO, _ rv: WdRevealDTO, _ now: Date) -> some View {
        let mine = rv.ownerId == model.me
        VStack(spacing: 10) {
            Text("IT WAS").bridgeLabel(10)
            Face(name: rv.ownerName, size: 84, index: g.players.firstIndex { $0.userId == rv.ownerId } ?? 0, ring: Theme.ok)
            Text(mine ? String(localized: "Your day") : String(localized: "\(rv.ownerName)'s day")).font(BFont.display(26)).foregroundStyle(Theme.ink).multilineTextAlignment(.center)
            Label {
                Text(rv.goalPrivate || rv.goalTitle == nil ? String(localized: "Private goal") : [rv.goalTitle, rv.deadline.map { String(localized: "by \(date($0))") }].compactMap { $0 }.joined(separator: " · "))
                    .font(BFont.body(13)).foregroundStyle(Theme.ink2)
            } icon: { Image(systemName: rv.goalPrivate ? "lock.fill" : "flag.fill").foregroundStyle(Theme.mut) }
        }
        .padding(.horizontal, 20).padding(.vertical, 24).frame(maxWidth: .infinity)
        .background(Theme.s1, in: RoundedRectangle(cornerRadius: 24, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(Theme.ok, lineWidth: 1.5))
        .accessibilityElement(children: .combine)
        .onAppear { AccessibilityNotification.Announcement(mine ? String(localized: "It was your day") : String(localized: "It was \(rv.ownerName)'s day")).post() }
        let me = rv.guesses.first { $0.userId == model.me }
        let right = me?.correct == true
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 4) {
                Text(mine ? "YOU · YOUR LIST" : right ? "YOU · RIGHT" : me?.guessId == nil ? "YOU · NO GUESS" : "YOU · WRONG" as LocalizedStringKey).bridgeLabel(9.5)
                HStack(spacing: 6) {
                    if right { Image(systemName: "checkmark.circle.fill").foregroundStyle(Theme.ok) }
                    Text(mine ? "+\(rv.ownerPoints)" : right ? "+\(me?.points ?? 0)" : "0").font(BFont.display(22)).foregroundStyle(right ? Theme.ok : Theme.ink)
                }
            }
            .padding(14).frame(maxWidth: .infinity, alignment: .leading).background(right ? Theme.okTint : Theme.bg, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .accessibilityElement(children: .combine)
            let owner = mine ? String(localized: "You") : rv.ownerName
            VStack(alignment: .leading, spacing: 4) {
                Text("\(owner.uppercased()) FOOLED \(rv.ownerPoints)").bridgeLabel(9.5).lineLimit(1)
                Text("+\(rv.ownerPoints) for \(owner)").font(BFont.display(18)).foregroundStyle(Theme.ink)
            }
            .padding(14).frame(maxWidth: .infinity, alignment: .leading).background(Theme.bg, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .accessibilityElement(children: .combine)
        }
        VStack(alignment: .leading, spacing: 8) {
            Text("GUESSES · \(rv.guesses.filter(\.correct).count) OF \(rv.guesses.count) GOT IT").bridgeLabel(10)
            ForEach(rv.guesses, id: \.userId) { x in
                let who = x.userId == model.me ? String(localized: "You") : model.name(x.userId)
                HStack(spacing: 10) {
                    Face(name: model.name(x.userId), size: 30, index: g.players.firstIndex { $0.userId == x.userId } ?? 0)
                    Text(x.guessId.map { String(localized: "\(who) said \($0 == model.me ? String(localized: "You") : model.name($0))") } ?? String(localized: "\(who) didn't guess"))
                        .font(BFont.body(13)).foregroundStyle(Theme.ink2).frame(maxWidth: .infinity, alignment: .leading)
                    if x.correct { Image(systemName: "checkmark").font(.system(size: 12, weight: .bold)).foregroundStyle(Theme.ok) }
                    Text(x.correct ? "+\(x.points)" : "0").font(BFont.label(12)).fontWeight(.semibold).foregroundStyle(x.correct ? Theme.ok : Theme.mut)
                }
                .padding(.horizontal, 12).padding(.vertical, 10).background(Theme.bg, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .accessibilityElement(children: .combine)
            }
        }
        HStack(spacing: 12) {
            CoachMascot(mood: .happy, size: 48)
            Text(rv.arcLine).font(BFont.body(14)).foregroundStyle(Theme.ink).padding(12).frame(maxWidth: .infinity, alignment: .leading)
                .background(Theme.bg, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .accessibilityElement(children: .combine)
        let last = r.index + 1 >= r.total
        Button(last ? String(localized: "See results") : String(localized: "Next list · \(r.index + 2) of \(r.total) · \(String(format: "0:%02d", model.secondsTo(g.endsAt, now)))"), action: actions.next)
            .buttonStyle(PrimaryButtonStyle()).disabled(model.nextTapped == g.current)
    }

    // MARK: Results

    @ViewBuilder private func results(_ g: WhoseDayDTO, _ res: WdResultsDTO) -> some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 6) {
                Text("Game over").font(BFont.display(30)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                Text("\(g.allianceName) · \(g.players.count) played").font(BFont.body(14)).foregroundStyle(Theme.mut)
            }
            .padding(.top, 6).frame(maxWidth: .infinity, alignment: .leading)
            CoachMascot(mood: .happy, size: 80)
        }
        .onAppear { AccessibilityNotification.Announcement(String(localized: "Game over")).post() }
        HStack(alignment: .top, spacing: 8) {
            award("SHARPEST EYE", "eye", res.sharpest.map { model.name($0.userId) }, res.sharpest.map { String(localized: "\($0.right) of \($0.of) right, \($0.early) of them early") })
            award("DARK HORSE", "lock.fill", res.darkHorse.map { model.name($0.userId) },
                  res.darkHorse.map { $0.lists == 1 ? String(localized: "Fooled \($0.fooled) people with 1 list") : String(localized: "Fooled \($0.fooled) people with \($0.lists) lists") })
        }
        VStack(alignment: .leading, spacing: 6) {
            Text("SCORES").bridgeLabel(10)
            ForEach(Array(res.scores.enumerated()), id: \.element.userId) { i, s in
                let me = s.userId == model.me
                HStack(spacing: 10) {
                    Text("\(i + 1)").font(BFont.label(12)).foregroundStyle(Theme.mut).frame(width: 16)
                    Face(name: model.name(s.userId), size: 30, index: g.players.firstIndex { $0.userId == s.userId } ?? 0)
                    Text(me ? String(localized: "You") : model.name(s.userId)).font(BFont.body(14, .medium)).foregroundStyle(Theme.ink).frame(maxWidth: .infinity, alignment: .leading)
                    Text("\(s.score) pts").font(BFont.label(13)).fontWeight(.semibold).foregroundStyle(Theme.ink)
                }
                .padding(.horizontal, 12).padding(.vertical, 10)
                .background(me ? Theme.accTint : Theme.bg, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(me ? Theme.accLine : .clear, lineWidth: 1))
                .accessibilityElement(children: .combine)
            }
        }
        if let f = res.favourite {
            let who = f.userId == model.me ? String(localized: "You") : model.name(f.userId)
            VStack(alignment: .leading, spacing: 6) {
                Text("ARC'S FAVOURITE TASK TONIGHT").bridgeLabel(10)
                Text(f.task).font(BFont.display(18)).foregroundStyle(Theme.ink)
                Text(f.streak >= 2 ? String(localized: "\(who) · \(f.streak) days running") : who).font(BFont.body(13)).foregroundStyle(Theme.mut)
            }
            .padding(16).frame(maxWidth: .infinity, alignment: .leading).background(Theme.bg, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .accessibilityElement(children: .combine)
        }
        Button("Share to alliance chat", action: actions.share).buttonStyle(PrimaryButtonStyle())
        HStack(spacing: 8) {
            Button("Rematch", action: actions.rematch).buttonStyle(SecondaryButtonStyle())
            Button("Done", action: actions.exit).buttonStyle(SecondaryButtonStyle())
        }
    }

    private func award(_ label: LocalizedStringKey, _ icon: String, _ name: String?, _ why: String?) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Image(systemName: icon).font(.system(size: 20)).foregroundStyle(Theme.ink2)
            Text(label).bridgeLabel(9.5)
            Text(name ?? String(localized: "Nobody yet")).font(BFont.display(18)).foregroundStyle(Theme.ink).lineLimit(1)
            if let why { Text(why).font(BFont.body(12)).foregroundStyle(Theme.mut) }
        }
        .padding(14).frame(maxWidth: .infinity, minHeight: 132, alignment: .topLeading)
        .background(Theme.bg, in: RoundedRectangle(cornerRadius: 18, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Theme.line, lineWidth: 1))
        .accessibilityElement(children: .combine)
    }
}

/// Initial on a coloured circle, cycling through the goal card fills.
private struct Face: View {
    let name: String
    let size: CGFloat
    let index: Int
    var ring: Color? = nil
    var body: some View {
        Circle().fill(Theme.goalCards[index % Theme.goalCards.count]).frame(width: ring == nil ? size : size - 16, height: ring == nil ? size : size - 16)
            .overlay(Text(name.prefix(1).uppercased()).font(BFont.display(size * 0.36)).foregroundStyle(Theme.goalInk))
            .frame(width: size, height: size)
            .overlay { if let ring { Circle().strokeBorder(ring, lineWidth: 3) } }
            .accessibilityHidden(true)
    }
}

private struct AvatarStack: View {
    let names: [String]
    var body: some View {
        HStack(spacing: -10) {
            ForEach(Array(names.prefix(7).enumerated()), id: \.offset) { i, n in
                Circle().fill(Theme.goalCards[i % Theme.goalCards.count]).frame(width: 28, height: 28)
                    .overlay(Text(n.prefix(1).uppercased()).font(BFont.display(12)).foregroundStyle(Theme.goalInk))
                    .padding(2).background(Theme.s1, in: Circle())
            }
        }
        .accessibilityHidden(true)
    }
}

/// "07:40" (the owner's local time) in this device's time format.
private func clock(_ hhmm: String) -> String {
    let f = DateFormatter(); f.dateFormat = "HH:mm"; f.timeZone = TimeZone(identifier: "UTC"); f.locale = Locale(identifier: "en_US_POSIX")
    guard let d = f.date(from: hhmm) else { return hhmm }
    return d.formatted(Date.FormatStyle(timeZone: TimeZone(identifier: "UTC")!).hour().minute())
}

private func date(_ iso: String) -> String {
    let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; f.timeZone = TimeZone(identifier: "UTC"); f.locale = Locale(identifier: "en_US_POSIX")
    guard let d = f.date(from: iso) else { return iso }
    return d.formatted(Date.FormatStyle(timeZone: TimeZone(identifier: "UTC")!).day().month(.abbreviated).year())
}
