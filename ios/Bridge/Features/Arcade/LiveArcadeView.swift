import SwiftUI

// MARK: World views

struct HoopsPlayer: Decodable { let userId: String; var made: Int; var shots: Int; var hoopX: Double }
struct HoopsWorld: Decodable { var startsAt: Double; var endsAt: Double; var players: [HoopsPlayer] }
struct HoopsShot: Decodable { var hoopX: Double; var path: [[Double]]; var made: Bool; var rim: Bool }

struct GolfBlock: Decodable { let x0: Double; let y0: Double; let x1: Double; let y1: Double }
struct GolfHole: Decodable { var cup: [Double]; var blocks: [GolfBlock] }
struct Golfer: Decodable { let userId: String; var hole: Int; var strokes: [Int]; var total: Int; var ball: [Double]; var done: Bool; var mulligan: Bool }
struct GolfWorld: Decodable { var holes: [GolfHole]; var cupR: Double; var ballR: Double; var endsAt: Double; var perks: [String]; var players: [Golfer] }
struct GolfPutt: Decodable { var hole: Int?; var rec: RecordingDTO?; var sunk: Bool?; var picked: Bool?; var strokes: Int? }

struct Puck: Decodable { let userId: String; var x: Double; var y: Double; var out: Bool; var aimed: Bool }
struct PushSlide: Decodable { var round: Int; var rec: RecordingDTO; var out: [String] }
struct PushWorld: Decodable { var round: Int; var maxRounds: Int; var phase: String; var endsAt: Double; var puckR: Double; var pucks: [Puck]; var last: PushSlide? }

@MainActor @Observable
final class LiveModel {
    var game: LiveGameDTO?
    var none = false
    var connected = true
    var offset: Double = 0
    /// This player's last shot (Hoops, Mini Golf), with a counter so the same result replays.
    var reply: JSONValue?
    var replyN = 0
    func serverNow(_ d: Date = .now) -> Double { d.timeIntervalSince1970 * 1000 + offset }
}

struct LiveArcadeScreen: View {
    let allianceId: String
    @Environment(SocialStore.self) private var social
    @Environment(SessionStore.self) private var session
    @Environment(Toaster.self) private var toaster
    @Environment(\.dismiss) private var dismiss
    @State private var model = LiveModel()

    var body: some View {
        LiveArcadeContent(model: model, me: session.user?.id.uuidString.lowercased() ?? "", close: { dismiss() },
                          ready: { frame("arc:ready") }, start: { frame("arc:start") }, move: { frame("arc:move", move: $0) })
            .toolbar(.hidden, for: .navigationBar)
            .task {
                let rt = social.realtime!
                rt.gameListener = { handle($0) }
                rt.onReady = { model.connected = true; join() }
                rt.acquire()
                await load()
            }
            .onDisappear { frame("arc:leave"); social.realtime.gameListener = nil; social.realtime.onReady = nil; social.realtime.release() }
    }

    private func load() async {
        do {
            let g = try await APIClient.shared.live(allianceId)
            if let g { model.game = g; model.offset = g.now - Date().timeIntervalSince1970 * 1000 }
            model.none = g == nil && model.game == nil
            join()
        } catch { model.connected = false }
    }
    private func join() { frame("arc:join") }
    private func frame(_ type: String, move: [String: Any]? = nil) {
        guard let id = model.game?.id else { return }
        var f: [String: Any] = ["type": type, "gameId": id]
        if let move { f["move"] = move }
        social.realtime.send(f)
    }
    private func handle(_ e: [String: Any]) {
        switch e["type"] as? String {
        case "arc:state":
            guard let g = decodeFrame(e["game"], as: LiveGameDTO.self), model.game == nil || g.id == model.game?.id else { return }
            model.game = g; model.offset = g.now - Date().timeIntervalSince1970 * 1000
        case "arc:you":
            guard e["gameId"] as? String == model.game?.id else { return }
            model.reply = decodeFrame(e["reply"], as: JSONValue.self); model.replyN += 1
        case "arc:error":
            switch e["error"] as? String {
            case "game_over": Task { await load() }
            case "need_players": toaster.show(String(localized: "Not enough players are ready yet."), .warn)
            default: break
            }
        default: break
        }
    }
}

struct LiveArcadeContent: View {
    let model: LiveModel
    let me: String
    var close: () -> Void = {}
    var ready: () -> Void = {}
    var start: () -> Void = {}
    var move: ([String: Any]) -> Void = { _ in }
    var now: Date?

    var body: some View {
        VStack(spacing: 0) {
            ArcadeTopBar(label: model.game.map { String(localized: "Game night · \(gameName($0.game))") } ?? String(localized: "Game night"), close: close)
            if !model.connected {
                Text("Reconnecting…").font(BFont.body(12, .medium)).foregroundStyle(Theme.warn).frame(maxWidth: .infinity).padding(8).background(Theme.warnTint)
            }
            if let g = model.game {
                TimelineView(.periodic(from: .now, by: 0.25)) { ctx in
                    ScrollView { content(g, serverNow: model.serverNow(now ?? ctx.date)).padding(.bottom, 20) }
                }
            } else if model.none {
                Text("No game is open right now.").font(BFont.body(15)).foregroundStyle(Theme.ink).padding(24)
                Spacer()
            } else { Spacer() }
        }
        .background(Theme.s1)
    }

    @ViewBuilder private func content(_ g: LiveGameDTO, serverNow: Double) -> some View {
        let player = g.players.first { $0.userId == me }
        let play = player.map { !$0.watching } ?? false
        let names = Dictionary(g.players.map { ($0.userId, $0.name) }, uniquingKeysWith: { a, _ in a })
        VStack(spacing: 14) {
            switch g.phase {
            case "lobby": lobby(g, serverNow: serverNow)
            case "playing":
                if !play { Text("You're watching this one. You can play the next.").font(BFont.body(13)).foregroundStyle(Theme.mut).padding(.horizontal, 16) }
                switch g.game {
                case "hoops": if let w = g.world?.decode(HoopsWorld.self) { HoopsPlay(w: w, me: me, names: names, play: play, model: model, serverNow: serverNow, move: move) }
                case "golf": if let w = g.world?.decode(GolfWorld.self) { GolfPlay(w: w, me: me, names: names, play: play, model: model, serverNow: serverNow, move: move) }
                case "push": if let w = g.world?.decode(PushWorld.self) { PushPlay(w: w, me: me, names: names, play: play, serverNow: serverNow, move: move) }
                default: Text("Update The Bridge to play this.").font(BFont.body(15)).foregroundStyle(Theme.ink).padding(24)
                }
            case "results": results(g)
            default: Text("Not enough players were ready, so this game didn't start.").font(BFont.body(15)).foregroundStyle(Theme.ink).padding(24)
            }
        }
    }

    private func lobby(_ g: LiveGameDTO, serverNow: Double) -> some View {
        let players = g.players.filter { !$0.watching }
        let readyCount = players.filter { $0.ready && !$0.absent }.count
        let mine = g.players.first { $0.userId == me }
        let left = max(0, Int((g.endsAt - serverNow) / 1000))
        let rules: LocalizedStringKey = g.game == "hoops" ? "30 seconds, everyone at once. Most baskets wins." : g.game == "golf" ? "Six holes, everyone at once. Fewest strokes wins." : "Everyone flicks at once. Knock the others off the ice. Last one on wins."
        return VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("GAME NIGHT · EVERYONE").bridgeLabel(10, color: Theme.acc)
                    Text(gameName(g.game)).font(BFont.display(30)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                    Text(rules).font(BFont.body(14)).foregroundStyle(Theme.mut)
                }
                Spacer()
                CoachMascot(mood: .happy, size: 64)
            }
            Text("\(readyCount) of \(players.count) ready · starts in \(String(format: "%d:%02d", left / 60, left % 60))").bridgeLabel(11)
            ForEach(players) { p in
                HStack(spacing: 10) {
                    Avatar(name: p.name, size: 30, ink: Theme.ink)
                    Text(p.name).font(BFont.body(15, .medium)).foregroundStyle(Theme.ink)
                    Spacer()
                    Text(p.ready ? "READY" : "JOINING").bridgeLabel(9.5, color: p.ready ? Theme.ok : Theme.mut)
                }
                .padding(12).background(Theme.s2, in: RoundedRectangle(cornerRadius: 14, style: .continuous)).accessibilityElement(children: .combine)
            }
            if mine == nil || mine?.watching == true { Text("You're watching this one. You can play the next.").font(BFont.body(13)).foregroundStyle(Theme.mut) }
            else if mine?.ready == false { Button("I'm ready", action: ready).buttonStyle(PrimaryButtonStyle()) }
            else if readyCount >= g.min { Button("Start now", action: start).buttonStyle(SecondaryButtonStyle()) }
            else { Text("Needs \(g.min) ready to start.").font(BFont.body(13)).foregroundStyle(Theme.mut) }
        }
        .padding(.horizontal, 20)
    }

    private func results(_ g: LiveGameDTO) -> some View {
        let rows = g.results ?? []
        let winners = rows.filter { $0.place == 1 }
        let title = winners.count == 1 && winners[0].userId == me ? String(localized: "You won") : winners.count == 1 ? String(localized: "\(winners[0].name) won") : String(localized: "Shared win: \(winners.map(\.name).joined(separator: ", "))")
        return VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("RESULT").bridgeLabel(10)
                    Text(title).font(BFont.display(26)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                }
                Spacer()
                CoachMascot(mood: .happy, size: 64)
            }
            ForEach(rows) { r in
                HStack(spacing: 10) {
                    Text("\(r.place)").font(BFont.display(18)).foregroundStyle(Theme.ink)
                    if r.place == 1 { Image(systemName: "trophy.fill").foregroundStyle(Theme.acc).accessibilityLabel(Text("First place")) }
                    Avatar(name: r.name, size: 28, ink: Theme.ink)
                    Text(r.name).font(BFont.body(15, .medium)).foregroundStyle(Theme.ink)
                    Spacer()
                    Text(score(g, r.score)).font(BFont.label(12)).foregroundStyle(Theme.ink)
                }
                .padding(12).background(r.userId == me ? Theme.accTint : Theme.s2, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay { if r.userId == me { RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Theme.accLine, lineWidth: 1) } }
                .accessibilityElement(children: .combine)
            }
            Button("Done", action: close).buttonStyle(SecondaryButtonStyle())
        }
        .padding(.horizontal, 20)
        .onAppear { AccessibilityNotification.Announcement(title).post() }
    }

    private func score(_ g: LiveGameDTO, _ s: Int) -> String {
        switch g.game {
        case "hoops": String(localized: "\(s) baskets")
        case "golf": String(localized: "\(s) strokes")
        default: s >= 900 ? String(localized: "Still on") : String(localized: "Out")
        }
    }
}

/// Leaderboard rows while playing.
private struct LiveBoard: View {
    let rows: [(String, String)]; let me: String; let names: [String: String]
    var body: some View {
        VStack(spacing: 6) {
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                let (id, value) = row
                HStack {
                    Text(id == me ? String(localized: "You") : names[id] ?? "").font(BFont.body(13, id == me ? .bold : .medium)).foregroundStyle(Theme.ink)
                    Spacer()
                    Text(value).font(BFont.label(12)).foregroundStyle(Theme.ink)
                }
                .accessibilityElement(children: .combine)
            }
        }
        .padding(12).background(Theme.s2, in: RoundedRectangle(cornerRadius: 16, style: .continuous)).padding(.horizontal, 16)
    }
}

// MARK: Hoops

private struct HoopsPlay: View {
    let w: HoopsWorld; let me: String; let names: [String: String]; let play: Bool; let model: LiveModel; let serverNow: Double; let move: ([String: Any]) -> Void
    @State private var aim: (angle: Double, power: Double)?
    var body: some View {
        let mine = w.players.first { $0.userId == me }
        let shot = model.reply?.decode(HoopsShot.self)
        let counting = serverNow < w.startsAt
        let left = max(0, min(30, Int(((w.endsAt - serverNow) / 1000).rounded(.up))))
        VStack(spacing: 14) {
            HStack(spacing: 10) {
                InfoTile(label: String(localized: "Baskets"), value: "\(mine?.made ?? 0)")
                InfoTile(label: String(localized: "Time"), value: counting ? String(localized: "Get ready") : String(format: "0:%02d", left), accent: true)
            }
            .padding(.horizontal, 16)
            Playback(frames: shot?.path.count ?? 1, key: AnyHashable(model.replyN)) { frame in
                GeometryReader { geo in
                    let wd = geo.size.width, h = geo.size.height
                    // Court units: x -0.6…0.6 across, y 0 (bottom) to 1.4 (top).
                    let p: (Double, Double) -> CGPoint = { x, y in CGPoint(x: CGFloat((x + 0.6) / 1.2) * wd, y: h - CGFloat(y / 1.4) * h) }
                    Canvas { ctx, size in
                        ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Surfaces.court))
                        let hoop = p(mine?.hoopX ?? 0, 1)
                        ctx.stroke(Path(CGRect(x: hoop.x - wd * 0.12, y: hoop.y - wd * 0.16, width: wd * 0.24, height: wd * 0.14)), with: .color(.white), lineWidth: 4)
                        ctx.stroke(Path(ellipseIn: CGRect(x: hoop.x - wd * 0.06, y: hoop.y - wd * 0.06, width: wd * 0.12, height: wd * 0.12)), with: .color(Color(light: 0xE2572B, dark: 0xE2572B)), lineWidth: 6)
                        if let a = aim {
                            let d: Double = 0.5 + a.power
                            let land: CGPoint = p(sin(a.angle) * d, cos(a.angle) * d)
                            let from: CGPoint = p(0, 0.05)
                            for k in 1...10 { let t = CGFloat(k) / 10; let q = CGPoint(x: from.x + (land.x - from.x) * t, y: from.y + (land.y - from.y) * t); ctx.fill(Path(ellipseIn: CGRect(x: q.x - 4, y: q.y - 4, width: 8, height: 8)), with: .color(.white.opacity(0.85))) }
                        }
                        let pt = shot?.path[safe: frame]
                        var ball: CGPoint = pt.map { p($0[0], $0[1]) } ?? p(0, 0.05)
                        ball.y -= CGFloat(pt?[safe: 2] ?? 0) * wd * 0.15
                        ctx.fill(Path(ellipseIn: CGRect(x: ball.x - wd * 0.045, y: ball.y - wd * 0.045, width: wd * 0.09, height: wd * 0.09)), with: .color(Color(light: 0xE2572B, dark: 0xE2572B)))
                    }
                    .gesture(DragGesture()
                        .onChanged { g in aim = (Double(min(0.75, max(-0.75, atan2(g.translation.width, -g.translation.height)))), Double(min(1, hypot(g.translation.width, g.translation.height) / (h * 0.6)))) }
                        .onEnded { _ in if let a = aim, play, !counting, left > 0 { move(["angle": a.angle, "power": a.power]) }; aim = nil })
                }
            }
            .aspectRatio(0.75, contentMode: .fit).clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous)).padding(.horizontal, 16)
            .accessibilityElement().accessibilityLabel(Text("Hoops"))
            Text("Swipe up to shoot. The hoop moves after every shot.").font(BFont.body(12)).foregroundStyle(Theme.mut)
            if let s = shot { Text(s.made ? "Basket." : "Missed.").font(BFont.body(14, .semibold)).foregroundStyle(s.made ? Theme.ok : Theme.mut) }
            LiveBoard(rows: w.players.sorted { $0.made > $1.made }.map { ($0.userId, "\($0.made)") }, me: me, names: names)
        }
    }
}

// MARK: Mini Golf

private struct GolfPlay: View {
    let w: GolfWorld; let me: String; let names: [String: String]; let play: Bool; let model: LiveModel; let serverNow: Double; let move: ([String: Any]) -> Void
    @State private var aim: (angle: Double, power: Double)?
    var body: some View {
        let mine = w.players.first { $0.userId == me }
        let putt = model.reply?.decode(GolfPutt.self)
        let left = max(0, Int((w.endsAt - serverNow) / 1000))
        VStack(spacing: 14) {
            Playback(frames: putt?.rec?.frames.count ?? 1, key: AnyHashable(model.replyN)) { frame in
                let rec = putt?.rec
                let replaying = rec != nil && frame < (rec?.frames.count ?? 1) - 1
                let holeIndex = replaying ? (putt?.hole ?? 0) : (mine?.hole ?? 0)
                VStack(spacing: 14) {
                    HStack(spacing: 10) {
                        InfoTile(label: String(localized: "Hole"), value: "\(holeIndex + 1) / \(w.holes.count)")
                        InfoTile(label: String(localized: "Strokes"), value: "\(mine?.strokes[safe: holeIndex] ?? 0) · " + String(localized: "\(mine?.total ?? 0) total"))
                        InfoTile(label: String(localized: "Time"), value: String(format: "%d:%02d", left / 60, left % 60), accent: true)
                    }
                    .padding(.horizontal, 16)
                    if let hole = w.holes[safe: holeIndex] {
                        course(hole, ball: replaying ? rec?.frames[safe: frame].map { ($0[0], $0[1]) } : mine.map { ($0.ball[0], $0.ball[1]) },
                               hideBall: replaying && putt?.sunk == true && frame >= (rec?.goneAt["0"] ?? .max), canPutt: play && mine?.done == false && !replaying)
                    }
                    if !replaying, let p = putt, let s = p.strokes {
                        Text(p.sunk == true ? String(localized: "In, in \(s).") : p.picked == true ? String(localized: "Picked up for a \(s).") : String(localized: "Stroke \(s)."))
                            .font(BFont.body(14, .semibold)).foregroundStyle(p.sunk == true ? Theme.ok : Theme.mut)
                    }
                }
            }
            Text("Pull back from the ball and let go to putt.").font(BFont.body(12)).foregroundStyle(Theme.mut)
            if w.perks.contains(me) {
                let can = mine?.mulligan == false
                InfoTile(label: String(localized: "Streak bonus"), value: can ? String(localized: "Take your last putt again") : String(localized: "Used"), accent: can,
                         action: can && play ? { move(["t": "mulligan"]) } : nil).padding(.horizontal, 16)
            }
            if let m = mine, m.done { Text("All six holes done: \(m.total) strokes.").font(BFont.body(14)).foregroundStyle(Theme.ink) }
            LiveBoard(rows: w.players.sorted { $0.total < $1.total }.map { ($0.userId, String(localized: "Hole \($0.hole + 1) · \($0.total)")) }, me: me, names: names)
        }
    }

    private func course(_ hole: GolfHole, ball: (Double, Double)?, hideBall: Bool, canPutt: Bool) -> some View {
        GeometryReader { geo in
            let wd = geo.size.width, h = geo.size.height
            let p: (Double, Double) -> CGPoint = { x, y in CGPoint(x: CGFloat(x) * wd, y: h - CGFloat(y / 2) * h) }
            Canvas { ctx, size in
                ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Surfaces.green))
                for b in hole.blocks { let tl = p(b.x0, b.y1), br = p(b.x1, b.y0); ctx.fill(Path(CGRect(x: tl.x, y: tl.y, width: br.x - tl.x, height: br.y - tl.y)), with: .color(Surfaces.wall)) }
                let cup = p(hole.cup[0], hole.cup[1]), cr = CGFloat(w.cupR) * wd
                ctx.fill(Path(ellipseIn: CGRect(x: cup.x - cr, y: cup.y - cr, width: cr * 2, height: cr * 2)), with: .color(Color(light: 0x101810, dark: 0x101810)))
                if let ball, !hideBall {
                    let o = p(ball.0, ball.1), br = CGFloat(w.ballR) * wd
                    if let a = aim {
                        let len: CGFloat = h * CGFloat(0.08 + a.power * 0.4)
                        ctx.stroke(Path { $0.move(to: o); $0.addLine(to: CGPoint(x: o.x + CGFloat(sin(a.angle)) * len, y: o.y - CGFloat(cos(a.angle)) * len)) }, with: .color(.white.opacity(0.8)), style: StrokeStyle(lineWidth: 3, dash: [10, 8]))
                    }
                    ctx.fill(Path(ellipseIn: CGRect(x: o.x - br, y: o.y - br, width: br * 2, height: br * 2)), with: .color(.white))
                }
            }
            .gesture(DragGesture()
                .onChanged { g in
                    // Pull back: the ball goes the other way. Course y runs up the screen.
                    let dx = -g.translation.width, dy = -g.translation.height
                    if hypot(dx, dy) > 8 { aim = (Double(atan2(dx, -dy)), Double(min(1, hypot(dx, dy) / (h * 0.5)))) }
                }
                .onEnded { _ in if let a = aim, canPutt { move(["angle": a.angle, "power": a.power]) }; aim = nil })
        }
        .aspectRatio(0.5, contentMode: .fit).clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous)).padding(.horizontal, 40)
        .accessibilityElement().accessibilityLabel(Text("Mini Golf"))
    }
}

// MARK: Push Out

private struct PushPlay: View {
    let w: PushWorld; let me: String; let names: [String: String]; let play: Bool; let serverNow: Double; let move: ([String: Any]) -> Void
    @State private var aim: (angle: Double, power: Double)?
    var body: some View {
        let order = w.pucks.map(\.userId)
        let mine = w.pucks.first { $0.userId == me }
        let sliding = w.phase == "slide" && w.last?.round == w.round
        let left = max(0, Int(((w.endsAt - serverNow) / 1000).rounded(.up)))
        VStack(spacing: 14) {
            HStack(spacing: 10) {
                InfoTile(label: String(localized: "Round"), value: "\(w.round) / \(w.maxRounds)")
                InfoTile(label: String(localized: "Still on"), value: "\(w.pucks.filter { !$0.out }.count)")
                InfoTile(label: w.phase == "aim" ? String(localized: "Aim") : String(localized: "Sliding"), value: w.phase == "aim" ? String(format: "0:%02d", left) : "…", accent: w.phase == "aim")
            }
            .padding(.horizontal, 16)
            Playback(frames: sliding ? (w.last?.rec.frames.count ?? 1) : 1, key: AnyHashable(sliding ? w.round : -w.round)) { frame in
                GeometryReader { geo in
                    let r = min(geo.size.width, geo.size.height) / 2 * 0.92
                    let o = CGPoint(x: geo.size.width / 2, y: geo.size.height / 2)
                    let p: (Double, Double) -> CGPoint = { x, y in CGPoint(x: o.x + CGFloat(x) * r, y: o.y - CGFloat(y) * r) }
                    Canvas { ctx, _ in
                        ctx.fill(Path(ellipseIn: CGRect(x: o.x - r, y: o.y - r, width: r * 2, height: r * 2)), with: .color(Surfaces.ice))
                        ctx.stroke(Path(ellipseIn: CGRect(x: o.x - r, y: o.y - r, width: r * 2, height: r * 2)), with: .color(Theme.ink.opacity(0.25)), lineWidth: 3)
                        let rec = w.last?.rec
                        for (i, pk) in w.pucks.enumerated() {
                            var x = pk.x, y = pk.y, out = pk.out
                            if sliding, let rec, frame < rec.frames.count - 1, let f = rec.frames[safe: frame] {
                                if let k = rec.ids.firstIndex(of: i) { x = f[k * 2]; y = f[k * 2 + 1] }
                                if let gone = rec.goneAt[String(i)] { out = frame >= gone } else { out = pk.out }
                            }
                            if out { continue }
                            let c = p(x, y), pr = CGFloat(w.puckR) * r
                            let rect = CGRect(x: c.x - pr, y: c.y - pr, width: pr * 2, height: pr * 2)
                            ctx.fill(Path(ellipseIn: rect), with: .color(arcadeFills[(order.firstIndex(of: pk.userId) ?? 0) % arcadeFills.count]))
                            ctx.stroke(Path(ellipseIn: rect), with: .color(arcadeInk), lineWidth: pk.userId == me ? 6 : 2)
                            if pk.aimed && w.phase == "aim" { ctx.fill(Path(ellipseIn: CGRect(x: c.x - pr * 0.25, y: c.y - pr * 0.25, width: pr * 0.5, height: pr * 0.5)), with: .color(arcadeInk)) }
                        }
                        if let a = aim, let m = mine {
                            let from: CGPoint = p(m.x, m.y)
                            let len: CGFloat = r * CGFloat(0.15 + a.power * 0.6)
                            ctx.stroke(Path { $0.move(to: from); $0.addLine(to: CGPoint(x: from.x + CGFloat(sin(a.angle)) * len, y: from.y - CGFloat(cos(a.angle)) * len)) }, with: .color(Theme.ink), style: StrokeStyle(lineWidth: 4, dash: [10, 8]))
                        }
                    }
                    .gesture(DragGesture()
                        .onChanged { g in
                            // Flick: pull back from your puck, it goes the other way.
                            let dx = -g.translation.width, dy = -g.translation.height
                            if hypot(dx, dy) > 8 { aim = (Double(atan2(dx, -dy)), Double(min(1, hypot(dx, dy) / (geo.size.width * 0.45)))) }
                        }
                        .onEnded { _ in if let a = aim, play, mine?.out == false, w.phase == "aim" { move(["angle": a.angle, "power": a.power]) }; aim = nil })
                }
            }
            .aspectRatio(1, contentMode: .fit).padding(.horizontal, 16)
            .accessibilityElement().accessibilityLabel(Text("Push Out"))
            Text(mine?.out == true ? "You're out. Watch how it ends." : mine?.aimed == true && w.phase == "aim" ? "Aimed. Waiting for the others." : "Pull back from your puck and let go to aim your flick.")
                .font(BFont.body(13)).foregroundStyle(Theme.mut).multilineTextAlignment(.center)
            LiveBoard(rows: w.pucks.map { ($0.userId, $0.out ? String(localized: "Out") : String(localized: "Still on")) }, me: me, names: names)
        }
    }
}
