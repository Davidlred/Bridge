import SwiftUI

/// One 1v1 match, live or turn by turn. The server checks every move; this sends moves and draws what comes back.
@MainActor @Observable
final class MatchModel {
    var match: MatchDTO?
    var failed = false
    var busy = false
    var connected = true
    /// How far the server's clock is ahead of this device's (ms).
    var offset: Double = 0
    func serverNow(_ d: Date = .now) -> Double { d.timeIntervalSince1970 * 1000 + offset }
    func set(_ m: MatchDTO) { match = m; failed = false; busy = false; if m.now > 0 { offset = m.now - Date().timeIntervalSince1970 * 1000 } }
}

struct MatchScreen: View {
    let matchId: String
    @Environment(SocialStore.self) private var social
    @Environment(AppRouter.self) private var router
    @Environment(Toaster.self) private var toaster
    @Environment(\.dismiss) private var dismiss
    @State private var model = MatchModel()

    var body: some View {
        MatchContent(model: model, actions: MatchActions(
            close: { dismiss() }, send: send, resign: { act { try await APIClient.shared.resign(matchId) } }, claim: { act { try await APIClient.shared.claim(matchId) } },
            rematch: { Task { do { let m = try await APIClient.shared.rematch(matchId); router.today.append(Route.match(m.id)) } catch { failedToast(error) } } },
            retry: { Task { await load() } },
            bracket: { id in router.today.append(Route.pairings(id)) }))
        .toolbar(.hidden, for: .navigationBar)
        .task {
            let rt = social.realtime!
            rt.gameListener = { handle($0) }
            rt.onReady = { model.connected = true; rt.send(["type": "match:watch", "matchId": matchId]) }
            rt.acquire()
            rt.send(["type": "match:watch", "matchId": matchId])
            await load()
        }
        .onDisappear {
            social.realtime.send(["type": "match:leave", "matchId": matchId])
            social.realtime.gameListener = nil; social.realtime.onReady = nil; social.realtime.release()
        }
    }

    private func load() async {
        do { model.set(try await APIClient.shared.match(matchId)) } catch { model.failed = model.match == nil }
    }

    private func handle(_ e: [String: Any]) {
        guard e["type"] as? String == "match:state", let m = decodeFrame(e["match"], as: MatchDTO.self), m.id == matchId else { return }
        model.set(m)
    }

    private func send(_ move: [String: Any]) {
        guard !model.busy else { return }
        model.busy = true
        Task {
            do { model.set(try await APIClient.shared.move(matchId, move)) } catch APIError.server(_, let code, _, _) {
                model.busy = false
                switch code {
                case "stale", "not_your_turn", "game_over": await load()
                case "column_full": toaster.show(String(localized: "That column is full."), .warn)
                case "slow_down": break
                default: toaster.show(String(localized: "That move didn't go through. Try again."), .warn)
                }
            } catch {
                model.busy = false
                toaster.show(String(localized: "Game Night needs a connection."), .warn)
            }
        }
    }

    private func act(_ call: @escaping () async throws -> MatchDTO) { Task { do { model.set(try await call()) } catch { failedToast(error) } } }
    private func failedToast(_ error: Error) {
        if case APIError.server(_, "too_many_open", _, _) = error { toaster.show(String(localized: "You already have three games going with them."), .warn) }
        else { toaster.show(String(localized: "Game Night needs a connection."), .warn) }
    }
}

struct MatchActions {
    var close: () -> Void = {}
    var send: ([String: Any]) -> Void = { _ in }
    var resign: () -> Void = {}
    var claim: () -> Void = {}
    var rematch: () -> Void = {}
    var retry: () -> Void = {}
    var bracket: ((String) -> Void)?
}

struct MatchContent: View {
    let model: MatchModel
    var actions = MatchActions()
    /// Fixed time for snapshots.
    var now: Date?

    var body: some View {
        VStack(spacing: 0) {
            ArcadeTopBar(label: model.match.map { String(localized: "\(gameName($0.game)) · vs \($0.opponent?.name ?? "")") } ?? String(localized: "Game night"), close: actions.close)
            if !model.connected {
                Text("Reconnecting…").font(BFont.body(12, .medium)).foregroundStyle(Theme.warn).frame(maxWidth: .infinity).padding(8).background(Theme.warnTint)
            }
            if let m = model.match {
                if !m.known || !playableGames.contains(m.game) {
                    notice(String(localized: "Update The Bridge to play this."), button: nil)
                } else {
                    ScrollView {
                        VStack(spacing: 14) {
                            gameBody(m)
                            if m.done { MatchResult(match: m, actions: actions) } else { MatchFooter(match: m, actions: actions) }
                        }
                        .padding(.bottom, 24)
                    }
                }
            } else if model.failed {
                notice(String(localized: "Game Night needs a connection."), button: String(localized: "Retry"))
            }
            Spacer(minLength: 0)
        }
        .background(Theme.s1)
    }

    @ViewBuilder private func gameBody(_ m: MatchDTO) -> some View {
        let busy = model.busy || m.done
        switch m.game {
        case "four": if let v = m.view?.decode(FourView.self) { FourGame(match: m, v: v, busy: busy, send: actions.send) }
        case "word", "scramble": if let v = m.view?.decode(WordView.self) { WordGame(match: m, v: v, model: model, now: now, send: actions.send) }
        case "darts": if let v = m.view?.decode(DartsView.self) { DartsGame(match: m, v: v, busy: busy, send: actions.send) }
        case "fleet": if let v = m.view?.decode(FleetView.self) { FleetGame(match: m, v: v, busy: busy, send: actions.send) }
        case "cup": if let v = m.view?.decode(CupView.self) { CupGame(match: m, v: v, busy: busy, send: actions.send) }
        case "pool": if let v = m.view?.decode(PoolView.self) { PoolGame(match: m, v: v, busy: busy, send: actions.send) }
        default: EmptyView()
        }
    }

    private func notice(_ text: String, button: String?) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(text).font(BFont.body(15)).foregroundStyle(Theme.ink)
            if let button { Button(button, action: actions.retry).buttonStyle(SecondaryButtonStyle()) }
        }
        .padding(24).frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Claim after 48 hours, a way back to the bracket, and resigning (two taps).
private struct MatchFooter: View {
    let match: MatchDTO
    let actions: MatchActions
    @State private var confirm = false
    var body: some View {
        VStack(spacing: 10) {
            if match.held { Text("They'll see it when they're done today.").font(BFont.body(13)).foregroundStyle(Theme.mut).frame(maxWidth: .infinity, alignment: .leading) }
            if match.canClaim {
                Text("No move from \(match.opponent?.name ?? "") in 48 hours. You can take the win.").font(BFont.body(13)).foregroundStyle(Theme.mut).frame(maxWidth: .infinity, alignment: .leading)
                Button("Claim the win", action: actions.claim).buttonStyle(SecondaryButtonStyle())
            }
            if let b = match.bracketId, let go = actions.bracket { Button("See the bracket") { go(b) }.buttonStyle(SecondaryButtonStyle()) }
            Button(confirm ? "Tap again to resign" : "Resign") { if confirm { actions.resign() } else { confirm = true } }
                .font(BFont.body(13, .medium)).foregroundStyle(confirm ? Theme.bad : Theme.mut).frame(minHeight: 44)
        }
        .padding(.horizontal, 16)
    }
}

private struct MatchResult: View {
    let match: MatchDTO
    let actions: MatchActions
    var body: some View {
        let them = match.opponent?.name ?? ""
        let won = match.winnerId == match.you
        let title = match.winnerId == nil ? String(localized: "A draw") : won ? String(localized: "You won") : String(localized: "\(them) won")
        let mine = match.scores[match.you] ?? 0
        let theirs = match.opponent.flatMap { match.scores[$0.id] } ?? 0
        let how: String? = switch match.result {
        case "resigned": won ? String(localized: "\(them) resigned.") : String(localized: "You resigned.")
        case "claimed", "deadline": String(localized: "Time ran out on this match.")
        case "left": String(localized: "\(them) left the alliance.")
        default: nil
        }
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("RESULT").bridgeLabel(10)
                    Text(title).font(BFont.display(26)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                    if match.scored { Text("\(mine)–\(theirs)").font(BFont.label(14)).foregroundStyle(Theme.ink) }
                    if let how { Text(how).font(BFont.body(13)).foregroundStyle(Theme.mut) }
                }
                Spacer()
                CoachMascot(mood: .happy, size: 64)
            }
            if let b = match.bracketId, let go = actions.bracket { Button("See the bracket") { go(b) }.buttonStyle(PrimaryButtonStyle()) }
            else { Button("Rematch", action: actions.rematch).buttonStyle(PrimaryButtonStyle()) }
            Button("Done", action: actions.close).buttonStyle(SecondaryButtonStyle())
        }
        .padding(18).background(Theme.s2, in: RoundedRectangle(cornerRadius: 20, style: .continuous)).padding(.horizontal, 16)
        .onAppear { AccessibilityNotification.Announcement(title).post() }
    }
}

// MARK: Four in a Row

struct FourGame: View {
    let match: MatchDTO; let v: FourView; let busy: Bool; let send: ([String: Any]) -> Void
    private func discName(_ i: Int) -> String { i == 0 ? String(localized: "Solid") : String(localized: "Rings") }
    var body: some View {
        let me = v.players.firstIndex(of: match.you) ?? 0
        let them = match.opponent?.name ?? ""
        VStack(spacing: 14) {
            HeaderStrip(me: myName(match), mine: discName(me), middle: turnText(match), them: them, theirs: discName(1 - me), myTurn: match.myTurn)
            HStack(spacing: 0) {
                ForEach(0..<7, id: \.self) { col in
                    let free = (0..<6).filter { (v.board[safe: $0 * 7 + col] ?? 0) == 0 }.count
                    Button { send(["col": col]) } label: {
                        VStack(spacing: 4) { ForEach(0..<6, id: \.self) { row in disc(row * 7 + col) } }.padding(.vertical, 2).contentShape(Rectangle())
                    }
                    .buttonStyle(.plain).disabled(!match.myTurn || busy || free == 0)
                    .accessibilityLabel(Text("Column \(col + 1), \(free) free")).accessibilityHint(Text("Drop a disc"))
                }
            }
            .padding(10).background(Theme.s2, in: RoundedRectangle(cornerRadius: 20, style: .continuous)).padding(.horizontal, 16)
            HStack(spacing: 10) {
                InfoTile(label: String(localized: "You play"), value: discName(me))
                InfoTile(label: String(localized: "Moves"), value: "\(v.moves) / 42")
            }
            .padding(.horizontal, 16)
            Text("Tap a column to drop a disc. Four in a line wins.").font(BFont.body(13)).foregroundStyle(Theme.mut).multilineTextAlignment(.center).padding(.horizontal, 16)
        }
    }
    /// Player 1: a solid disc. Player 2: a ring. So the two never depend on colour alone.
    private func disc(_ cell: Int) -> some View {
        let owner = v.board[safe: cell] ?? 0
        return ZStack {
            Circle().fill(Theme.s1)
            if owner == 1 { Circle().fill(Theme.acc).padding(4) }
            if owner == 2 { Circle().strokeBorder(Theme.ink, lineWidth: 5).padding(4) }
            if v.line.contains(cell) { Circle().strokeBorder(Theme.ok, lineWidth: 3) }
            if v.last == cell && owner != 0 { Circle().fill(owner == 1 ? Theme.onAcc : Theme.ink).frame(width: 6, height: 6) }
        }
        .aspectRatio(1, contentMode: .fit).padding(.horizontal, 2)
    }
}

// MARK: Word Dash and Scramble

struct WordGame: View {
    let match: MatchDTO; let v: WordView; let model: MatchModel; var now: Date?; let send: ([String: Any]) -> Void
    var body: some View {
        let mine = v.rounds[match.you]
        let other = match.opponent
        let theirs = other.flatMap { v.rounds[$0.id] }
        let dash = v.game == "word"
        let playing = mine?.started == true && mine?.ended == false && match.myTurn
        let toBeat = theirs?.ended == true ? theirs?.score : nil
        VStack(spacing: 14) {
            HeaderStrip(me: myName(match), mine: "\(mine?.score ?? 0)", middle: turnText(match), them: other?.name ?? "",
                        theirs: theirs?.ended == true || match.done ? "\(theirs?.score ?? 0)" : "–", myTurn: match.myTurn)
            if match.myTurn && mine?.started != true {
                VStack(alignment: .leading, spacing: 10) {
                    Text(dash ? "A 4 by 4 grid and \(v.seconds) seconds. Drag across touching letters to make words. Longer words score more." : "Six letters, \(v.seconds) seconds. Make as many words as you can from them.")
                        .font(BFont.body(15)).foregroundStyle(Theme.ink)
                    if let toBeat { Text("\(other?.name ?? "") scored \(toBeat). Beat it.").font(BFont.body(14, .semibold)).foregroundStyle(Theme.ink) }
                    Text("Words from your alliance's plan score 2 extra.").font(BFont.body(13)).foregroundStyle(Theme.mut)
                    Button("Start my round") { send(["t": "start"]) }.buttonStyle(PrimaryButtonStyle()).disabled(model.busy)
                }
                .padding(18).background(Theme.s2, in: RoundedRectangle(cornerRadius: 20, style: .continuous)).padding(.horizontal, 16)
            } else if playing, let mine {
                WordPlay(v: v, round: mine, toBeat: toBeat, them: other?.name ?? "", dash: dash, model: model, now: now, send: send)
            } else if !match.done {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Your round is in: \(mine?.score ?? 0) points. Now it's \(other?.name ?? "")'s turn.").font(BFont.body(15)).foregroundStyle(Theme.ink)
                    FoundList(found: mine?.found ?? [])
                }
                .frame(maxWidth: .infinity, alignment: .leading).padding(18).background(Theme.s2, in: RoundedRectangle(cornerRadius: 20, style: .continuous)).padding(.horizontal, 16)
            } else {
                VStack(alignment: .leading, spacing: 12) {
                    Text(wordsLabel(String(localized: "You"), mine?.found.count ?? 0)).bridgeLabel(10)
                    FoundList(found: mine?.found ?? [])
                    Text(wordsLabel(other?.name ?? "", theirs?.found.count ?? 0)).bridgeLabel(10)
                    FoundList(found: theirs?.found ?? [])
                }
                .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16)
            }
        }
    }
}

/// "YOU · 1 WORD", "SAM · 9 WORDS".
func wordsLabel(_ who: String, _ n: Int) -> String {
    (n == 1 ? String(localized: "\(who) · 1 word") : String(localized: "\(who) · \(n) words")).uppercased()
}

struct FoundList: View {
    let found: [FoundWord]
    var body: some View {
        FlowLayout(spacing: 6) {
            ForEach(found, id: \.self) { f in
                Text("\(f.w.uppercased()) +\(f.pts)").font(BFont.label(10)).foregroundStyle(Theme.ok).padding(.horizontal, 9).padding(.vertical, 5)
                    .background(Theme.okTint, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            }
        }
    }
}

private func touching(_ a: Int, _ b: Int) -> Bool { a != b && abs(a / 4 - b / 4) <= 1 && abs(a % 4 - b % 4) <= 1 }

private struct WordPlay: View {
    let v: WordView; let round: WordRound; let toBeat: Int?; let them: String; let dash: Bool; let model: MatchModel; var now: Date?
    let send: ([String: Any]) -> Void
    @State private var path: [Int] = []
    @State private var sentEnd = false
    @State private var gestureStart: (count: Int, tile: Int?)?

    /// The tile under a point; only the middle of a tile counts, so a diagonal drag doesn't clip its neighbours.
    private func tile(_ p: CGPoint, _ cell: CGFloat) -> Int? {
        let col = Int(p.x / cell), row = Int(p.y / cell)
        guard (0..<4).contains(col), (0..<4).contains(row) else { return nil }
        let cx = (CGFloat(col) + 0.5) * cell, cy = (CGFloat(row) + 0.5) * cell
        return hypot(p.x - cx, p.y - cy) < cell * 0.42 ? row * 4 + col : nil
    }

    var body: some View {
        TimelineView(.periodic(from: .now, by: 0.5)) { ctx in
            let left = round.endsAt.map { max(0, min(v.seconds, Int((($0 - model.serverNow(now ?? ctx.date)) / 1000).rounded(.up)))) } ?? v.seconds
            content(left)
                .onChange(of: left == 0) { _, out in if out && !sentEnd { sentEnd = true; send(["t": "end"]) } }   // out of time: close the round
        }
    }

    private func submit() {
        guard path.count >= 3 else { return }
        send(["t": "word", "path": path]); path = []
    }

    @ViewBuilder private func content(_ left: Int) -> some View {
        let current = path.map { v.letters[safe: $0] ?? "" }.joined().uppercased()
        VStack(spacing: 12) {
            HStack(spacing: 8) {
                InfoTile(label: String(localized: "You"), value: "\(round.score)")
                InfoTile(label: String(localized: "Time"), value: String(format: "%d:%02d", left / 60, left % 60), accent: true)
                InfoTile(label: String(localized: "To beat"), value: toBeat.map { "\(them) \($0)" } ?? "–")
            }
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(Theme.track)
                    Capsule().fill(Theme.acc).frame(width: geo.size.width * CGFloat(left) / CGFloat(max(1, v.seconds)))
                }
            }
            .frame(height: 6).accessibilityHidden(true)
            Text(current.isEmpty ? " " : current.map(String.init).joined(separator: " ")).font(BFont.display(30)).foregroundStyle(Theme.ink).frame(maxWidth: .infinity)
            Text(feedback).font(BFont.body(13, .semibold)).foregroundStyle(round.last?.ok == true ? Theme.ok : Theme.mut).frame(maxWidth: .infinity)
                .onChange(of: feedback) { _, t in AccessibilityNotification.Announcement(t).post() }
            if dash { grid } else { rack }
            HStack(spacing: 10) {
                Button("Clear") { path = [] }.buttonStyle(SecondaryButtonStyle())
                Button("Submit", action: submit).buttonStyle(PrimaryButtonStyle()).disabled(path.count < 3)
            }
            Text((round.found.count == 1 ? String(localized: "Found · 1 word") : String(localized: "Found · \(round.found.count) words")).uppercased()).bridgeLabel(10).frame(maxWidth: .infinity, alignment: .leading)
            FoundList(found: round.found.reversed()).frame(maxWidth: .infinity, alignment: .leading)
            Text(dash ? "Drag across touching letters, or tap them in order and press Submit." : "Tap letters in order, then Submit.").font(BFont.body(12)).foregroundStyle(Theme.mut)
            Button("I'm done") { send(["t": "end"]) }.font(BFont.body(13, .medium)).foregroundStyle(Theme.mut).frame(minHeight: 44)
        }
        .padding(.horizontal, 16)
    }

    private var feedback: String {
        guard let l = round.last else { return " " }
        if l.ok { return l.bonus ? String(localized: "+\(l.pts) · bonus word from your plan") : "+\(l.pts)" }
        switch l.reason {
        case "already_found": return String(localized: "Already found")
        case "too_short": return String(localized: "Three letters or more")
        default: return String(localized: "Not a word")
        }
    }

    private func extend(_ i: Int?) {
        guard let i, !path.contains(i), path.last.map({ touching($0, i) }) ?? true else { return }
        path.append(i)
    }

    /// 4×4: drag across touching letters (sent on release), or tap them in order and press Submit.
    private var grid: some View {
        GeometryReader { geo in
            let cell = geo.size.width / 4
            VStack(spacing: 0) {
                ForEach(0..<4, id: \.self) { row in
                    HStack(spacing: 0) {
                        ForEach(0..<4, id: \.self) { col in
                            let i = row * 4 + col
                            let on = path.contains(i)
                            let letter = (v.letters[safe: i] ?? "").uppercased()
                            Text(letter).font(BFont.display(30)).foregroundStyle(on ? Theme.onAcc : Theme.ink)
                                .frame(width: cell - 8, height: cell - 8)
                                .background(on ? Theme.acc : Theme.s1, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                                .frame(width: cell, height: cell)
                                .accessibilityElement().accessibilityLabel(Text(letter)).accessibilityAddTraits(on ? [.isButton, .isSelected] : .isButton)
                                .accessibilityAction { if on && path.last == i { path.removeLast() } else { extend(i) } }
                        }
                    }
                }
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { g in
                    if gestureStart == nil { gestureStart = (path.count, tile(g.startLocation, cell)) }
                    extend(tile(g.location, cell))
                }
                .onEnded { g in
                    let start = gestureStart; gestureStart = nil
                    if hypot(g.translation.width, g.translation.height) > 20 { submit(); return }
                    // A tap on the last letter (that didn't add anything) takes it back.
                    if let s = start, path.count == s.count, let t = s.tile, path.last == t { path.removeLast() }
                })
        }
        .aspectRatio(1, contentMode: .fit)
        .padding(10).background(Theme.s2, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    /// Scramble: six letters, tap them in order (each once).
    private var rack: some View {
        HStack(spacing: 6) {
            ForEach(Array(v.letters.enumerated()), id: \.offset) { i, l in
                let on = path.contains(i)
                Button { path.append(i) } label: {
                    Text(l.uppercased()).font(BFont.display(26)).foregroundStyle(on ? Theme.mut : Theme.ink).frame(maxWidth: .infinity).aspectRatio(0.8, contentMode: .fit)
                        .background(on ? Theme.track : Theme.s1, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                .buttonStyle(.plain).disabled(on).accessibilityLabel(Text(l)).accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .padding(8).background(Theme.s2, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}

// MARK: Fleet Battle

struct FleetGame: View {
    let match: MatchDTO; let v: FleetView; let busy: Bool; let send: ([String: Any]) -> Void
    var body: some View {
        let them = match.opponent?.name ?? ""
        let myShips = v.mine.ships ?? []
        let myLeft = myShips.filter { !$0.cells.allSatisfy(v.mine.shots.contains) }.count
        VStack(alignment: .leading, spacing: 14) {
            HeaderStrip(me: myName(match), mine: String(localized: "\(myLeft) afloat"), middle: turnText(match), them: them, theirs: String(localized: "\(v.theirs.left) afloat"), myTurn: match.myTurn)
            if v.placed[match.you] != true {
                VStack(alignment: .leading, spacing: 10) {
                    Text("Hide five ships on a 10 by 10 grid, then take turns firing. Sink all of theirs to win.").font(BFont.body(15)).foregroundStyle(Theme.ink)
                    Button("Place my ships") { send(["t": "auto"]) }.buttonStyle(PrimaryButtonStyle()).disabled(busy)
                }
                .padding(18).background(Theme.s2, in: RoundedRectangle(cornerRadius: 20, style: .continuous)).padding(.horizontal, 16)
            } else {
                if v.next == nil && !match.done { Text("Waiting for \(them) to place their ships.").font(BFont.body(14)).foregroundStyle(Theme.mut).padding(.horizontal, 16) }
                Text("\(them.uppercased())'S WATERS").bridgeLabel(10).padding(.horizontal, 16)
                let fired = Dictionary(v.theirs.shots.map { ($0.cell, $0.hit) }, uniquingKeysWith: { a, _ in a })
                let sunk = Set(v.theirs.ships.flatMap(\.cells))
                grid { cell in
                    let shot = fired[cell]
                    let state: String? = sunk.contains(cell) ? String(localized: "Sunk") : shot == true ? String(localized: "Hit") : shot == false ? String(localized: "Miss") : nil
                    cellView(cell, state: state, fill: sunk.contains(cell) ? Theme.bad : shot == true ? Theme.acc : shot == false ? Theme.track : Theme.s1, mark: shot != nil,
                             fire: match.myTurn && !busy && shot == nil ? { send(["t": "fire", "cell": cell]) } : nil)
                }
                .padding(.horizontal, 16)
                HStack(spacing: 10) {
                    InfoTile(label: String(localized: "Ships sunk"), value: "\(5 - v.theirs.left) / 5")
                    if let l = v.last { InfoTile(label: String(localized: "Last shot"), value: l.sunk != nil ? String(localized: "Sunk") : l.hit ? String(localized: "Hit") : String(localized: "Miss")) }
                }
                .padding(.horizontal, 16)
                Text("YOUR WATERS").bridgeLabel(10).padding(.horizontal, 16)
                let ships = Set(myShips.flatMap(\.cells))
                grid { cell in
                    let hit = v.mine.shots.contains(cell), ship = ships.contains(cell)
                    let state = [ship ? String(localized: "Your ship") : nil, hit ? (ship ? String(localized: "Hit") : String(localized: "Miss")) : nil].compactMap { $0 }.joined(separator: ", ")
                    cellView(cell, state: state.isEmpty ? nil : state, fill: ship && hit ? Theme.bad : ship ? Theme.ink2 : hit ? Theme.track : Theme.s1, mark: hit, fire: nil)
                }
                .padding(.horizontal, 56)
            }
        }
    }

    private func grid<C: View>(@ViewBuilder _ cell: @escaping (Int) -> C) -> some View {
        VStack(spacing: 2) {
            ForEach(0..<10, id: \.self) { row in HStack(spacing: 2) { ForEach(0..<10, id: \.self) { col in cell(row * 10 + col) } } }
        }
        .padding(6).background(Theme.s2, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    @ViewBuilder private func cellView(_ cell: Int, state: String?, fill: Color, mark: Bool, fire: (() -> Void)?) -> some View {
        let name = "\(Character(UnicodeScalar(UInt8(65 + cell % 10))))\(cell / 10 + 1)"
        let square = RoundedRectangle(cornerRadius: 4, style: .continuous).fill(fill).aspectRatio(1, contentMode: .fit)
            .overlay { if mark { Circle().fill(Theme.ink).frame(width: 5, height: 5) } }
        if let fire {
            Button(action: fire) { square }.buttonStyle(.plain).accessibilityLabel(Text([name, state].compactMap { $0 }.joined(separator: ", "))).accessibilityHint(Text("Fire"))
        } else {
            square.accessibilityElement().accessibilityLabel(Text([name, state].compactMap { $0 }.joined(separator: ", ")))
        }
    }
}
