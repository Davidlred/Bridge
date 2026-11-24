import SwiftUI

// MARK: Darts

private let sectors = [20, 1, 18, 4, 13, 6, 10, 15, 2, 17, 3, 19, 7, 16, 8, 11, 14, 9, 12, 5]
private let boardDark = Color(light: 0x1E1E1E, dark: 0x1E1E1E)
private let boardLight = Color(light: 0xF1E6C8, dark: 0xF1E6C8)
private let boardRed = Color(light: 0xC8102E, dark: 0xC8102E)
private let boardGreen = Color(light: 0x00843D, dark: 0x00843D)

struct DartsGame: View {
    let match: MatchDTO; let v: DartsView; let busy: Bool; let send: ([String: Any]) -> Void
    var body: some View {
        let me = v.players.firstIndex(of: match.you) ?? 0
        let canThrow = match.myTurn && !busy
        let shown = v.darts.isEmpty ? (v.lastTurn?.darts ?? []) : v.darts
        VStack(spacing: 14) {
            HeaderStrip(me: myName(match), mine: "\(v.remaining[safe: me] ?? 301)", middle: match.myTurn ? String(localized: "Your dart · \(v.darts.count + 1) of 3") : turnText(match),
                        them: match.opponent?.name ?? "", theirs: "\(v.remaining[safe: 1 - me] ?? 301)", myTurn: match.myTurn)
            GeometryReader { geo in
                let size = min(geo.size.width, geo.size.height)
                let r = size / 2 / 1.18
                Canvas { ctx, sz in
                    let o = CGPoint(x: sz.width / 2, y: sz.height / 2)
                    ctx.fill(Path(ellipseIn: CGRect(x: o.x - r * 1.18, y: o.y - r * 1.18, width: r * 2.36, height: r * 2.36)), with: .color(boardDark))
                    func ring(_ r0: CGFloat, _ r1: CGFloat, _ a: Color, _ b: Color) {
                        for i in 0..<20 {
                            var p = Path()
                            p.move(to: o)
                            p.addArc(center: o, radius: r1, startAngle: .degrees(-99 + Double(i) * 18), endAngle: .degrees(-81 + Double(i) * 18), clockwise: false)
                            p.closeSubpath()
                            ctx.fill(p, with: .color(i % 2 == 0 ? a : b))
                        }
                        ctx.fill(Path(ellipseIn: CGRect(x: o.x - r0, y: o.y - r0, width: r0 * 2, height: r0 * 2)), with: .color(boardDark))
                    }
                    ring(162 / 170 * r, r, boardRed, boardGreen)
                    ring(107 / 170 * r, 162 / 170 * r, boardDark, boardLight)
                    ring(99 / 170 * r, 107 / 170 * r, boardRed, boardGreen)
                    ring(15.9 / 170 * r, 99 / 170 * r, boardDark, boardLight)
                    let ob = 15.9 / 170 * r, b = 6.35 / 170 * r
                    ctx.fill(Path(ellipseIn: CGRect(x: o.x - ob, y: o.y - ob, width: ob * 2, height: ob * 2)), with: .color(boardGreen))
                    ctx.fill(Path(ellipseIn: CGRect(x: o.x - b, y: o.y - b, width: b * 2, height: b * 2)), with: .color(boardRed))
                    for (i, n) in sectors.enumerated() {
                        let a = (-90 + Double(i) * 18) * .pi / 180
                        ctx.draw(Text("\(n)").font(BFont.label(11)).foregroundStyle(.white), at: CGPoint(x: o.x + cos(a) * r * 1.09, y: o.y + sin(a) * r * 1.09))
                    }
                    for d in shown {
                        let p = CGPoint(x: o.x + d.x * r, y: o.y - d.y * r)
                        ctx.fill(Path(ellipseIn: CGRect(x: p.x - 7, y: p.y - 7, width: 14, height: 14)), with: .color(.white))
                        ctx.fill(Path(ellipseIn: CGRect(x: p.x - 4, y: p.y - 4, width: 8, height: 8)), with: .color(arcadeInk))
                    }
                }
                .contentShape(Rectangle())
                .onTapGesture { p in
                    guard canThrow else { return }
                    // Board units: the double ring's outside is 1.
                    send(["t": "throw", "x": Double((p.x - geo.size.width / 2) / r), "y": Double(-(p.y - geo.size.height / 2) / r)])
                }
            }
            .aspectRatio(1, contentMode: .fit).padding(.horizontal, 24)
            .accessibilityElement().accessibilityLabel(Text(shown.isEmpty ? String(localized: "Dartboard") : shown.map(\.label).joined(separator: ", ")))
            HStack(spacing: 10) {
                let turn = !v.darts.isEmpty ? v.darts.map(\.label).joined(separator: "  ") : v.lastTurn.map { t in (t.bust ? String(localized: "Bust") + " · " : "") + t.darts.map(\.label).joined(separator: "  ") } ?? "–"
                InfoTile(label: v.darts.isEmpty ? String(localized: "Last turn") : String(localized: "This turn"), value: turn)
                if v.perks.contains(match.you) {
                    let perk = !v.perkUsed.contains(match.you)
                    InfoTile(label: String(localized: "Streak bonus"), value: perk ? String(localized: "Re-throw your last dart") : String(localized: "Used"), accent: perk,
                             action: perk && canThrow && !v.darts.isEmpty ? { send(["t": "rethrow"]) } : nil)
                }
            }
            .padding(.horizontal, 16)
            Text("Tap where you aim. Each dart wobbles a little. Finish on a double.").font(BFont.body(12)).foregroundStyle(Theme.mut).multilineTextAlignment(.center).padding(.horizontal, 16)
        }
    }
}

// MARK: Cup Pong

struct CupGame: View {
    let match: MatchDTO; let v: CupView; let busy: Bool; let send: ([String: Any]) -> Void
    @State private var aim: (angle: Double, power: Double)?
    var body: some View {
        let other = match.opponent
        let theirCups = v.cups[other?.id ?? ""] ?? []
        let myCups = v.cups[match.you] ?? []
        let spots = v.spots[other?.id ?? ""] ?? []
        let canThrow = match.myTurn && !busy
        let mineLast = v.last?.by == match.you ? v.last : nil
        VStack(spacing: 14) {
            HeaderStrip(me: myName(match), mine: String(localized: "\(myCups.count) cups"), middle: match.myTurn ? String(localized: "Your throw · \(3 - v.throwsLeft) of 2") : turnText(match),
                        them: other?.name ?? "", theirs: String(localized: "\(theirCups.count) cups"), myTurn: match.myTurn)
            Playback(frames: mineLast?.path.count ?? 1, key: AnyHashable(v.turns.reduce(0, +) * 2 + (2 - v.throwsLeft))) { frame in
                GeometryReader { geo in
                    let w = geo.size.width, h = geo.size.height
                    // Table units to points: x -0.5…0.5 across, y 0 (thrower, bottom) to 2 (top).
                    let p = { (x: Double, y: Double) in CGPoint(x: (x + 0.5) * w, y: h - y / 2 * h) }
                    Canvas { ctx, _ in
                        ctx.fill(Path(CGRect(x: 0, y: 0, width: w, height: h)), with: .linearGradient(Gradient(colors: [Surfaces.tableTop, Surfaces.tableBottom]), startPoint: .zero, endPoint: CGPoint(x: 0, y: h)))
                        ctx.stroke(Path { $0.move(to: CGPoint(x: w / 2, y: 0)); $0.addLine(to: CGPoint(x: w / 2, y: h)) }, with: .color(.white.opacity(0.25)), lineWidth: 2)
                        let cr = 0.045 * w
                        for (i, s) in spots.enumerated() where s.count == 2 {
                            let o = p(s[0], s[1]), rect = CGRect(x: o.x - cr, y: o.y - cr, width: cr * 2, height: cr * 2)
                            if theirCups.contains(i) { ctx.fill(Path(ellipseIn: rect), with: .color(Surfaces.cup)); ctx.stroke(Path(ellipseIn: rect), with: .color(.white), lineWidth: 3) }
                            else { ctx.stroke(Path(ellipseIn: rect), with: .color(.white.opacity(0.5)), style: StrokeStyle(lineWidth: 2, dash: [8, 6])) }
                        }
                        if let a = aim {
                            let d = 0.9 + a.power * 1.2
                            let land = p(sin(a.angle) * d, cos(a.angle) * d), from = p(0, 0.05)
                            for k in 1...12 { let t = CGFloat(k) / 12; let q = CGPoint(x: from.x + (land.x - from.x) * t, y: from.y + (land.y - from.y) * t); ctx.fill(Path(ellipseIn: CGRect(x: q.x - 3.5, y: q.y - 3.5, width: 7, height: 7)), with: .color(.white.opacity(0.8))) }
                            ctx.stroke(Path(ellipseIn: CGRect(x: land.x - cr * 1.3, y: land.y - cr * 1.3, width: cr * 2.6, height: cr * 2.6)), with: .color(Color(light: 0x8FD17A, dark: 0x8FD17A)), lineWidth: 3)
                        }
                        let pt = mineLast?.path[safe: frame]
                        let ball = pt.map { p($0[0], $0[1]) } ?? p(0, 0.05)
                        ctx.fill(Path(ellipseIn: CGRect(x: ball.x - w * 0.025, y: ball.y - w * 0.025, width: w * 0.05, height: w * 0.05)), with: .color(.white))
                    }
                    .gesture(DragGesture()
                        .onChanged { g in
                            // Swipe up to throw: sideways turns it, a longer swipe throws further.
                            aim = (Double(min(0.55, max(-0.55, atan2(g.translation.width, -g.translation.height)))), Double(min(1, hypot(g.translation.width, g.translation.height) / (h * 0.55))))
                        }
                        .onEnded { _ in if let a = aim, canThrow { send(["t": "throw", "angle": a.angle, "power": a.power]) }; aim = nil })
                }
            }
            .aspectRatio(0.62, contentMode: .fit).clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous)).padding(.horizontal, 16)
            .accessibilityElement().accessibilityLabel(Text("\(theirCups.count) cups"))
            Text("Swipe up to throw · harder goes further").font(BFont.body(12)).foregroundStyle(Theme.mut)
            HStack(spacing: 10) {
                if v.perks.contains(match.you) {
                    let perk = !v.perkUsed.contains(match.you)
                    InfoTile(label: String(localized: "Streak bonus"), value: perk ? String(localized: "Re-rack once") : String(localized: "Used"), accent: perk,
                             action: perk && canThrow && v.throwsLeft == 2 ? { send(["t": "rerack"]) } : nil)
                }
                InfoTile(label: String(localized: "Next"), value: match.myTurn ? String(localized: "\(other?.name ?? "")'s turn, now or later today") : turnText(match))
            }
            .padding(.horizontal, 16)
            if let t = v.last {
                let who = t.by == match.you ? String(localized: "You") : other?.name ?? ""
                Text(t.cup != nil ? String(localized: "\(who) sank a cup.") : t.rim ? String(localized: "\(who) hit the rim.") : String(localized: "\(who) missed."))
                    .font(BFont.body(13)).foregroundStyle(Theme.ink)
            }
        }
    }
}

// MARK: 8-Ball

private let ballColors: [Int: Color] = ([1: 0xF2C230, 2: 0x2456C8, 3: 0xD9342B, 4: 0x6A2C91, 5: 0xF07F1B, 6: 0x1F8A4C, 7: 0x7A2420] as [Int: UInt32]).mapValues { Color(light: $0, dark: $0) }
private func ballColor(_ id: Int) -> Color { id == 0 ? .white : id == 8 ? Color(light: 0x151515, dark: 0x151515) : ballColors[id > 8 ? id - 8 : id] ?? .gray }

struct PoolGame: View {
    let match: MatchDTO; let v: PoolView; let busy: Bool; let send: ([String: Any]) -> Void
    @State private var aim: (angle: Double, power: Double)?
    private func left(_ g: String?) -> Int { guard let g else { return 7 }; return v.balls.filter { !$0.in && (g == "solids" ? (1...7).contains($0.id) : (9...15).contains($0.id)) }.count }
    private func groupName(_ g: String?) -> String { g == "solids" ? String(localized: "Solids (1–7)") : g == "stripes" ? String(localized: "Stripes (9–15)") : String(localized: "Open table") }

    var body: some View {
        let other = match.opponent
        let mine = v.groups[match.you] ?? nil
        let theirs = other.flatMap { v.groups[$0.id] ?? nil }
        let rec = v.last?.rec
        VStack(spacing: 14) {
            HeaderStrip(me: myName(match), mine: "\(7 - left(mine))", middle: turnText(match), them: other?.name ?? "", theirs: "\(7 - left(theirs))", myTurn: match.myTurn)
            Playback(frames: rec?.frames.count ?? 1, key: AnyHashable(v.shots)) { frame in
                table(rec: rec, frame: frame, canShoot: match.myTurn && !busy && (rec == nil || frame >= (rec?.frames.count ?? 1) - 1))
            }
            .aspectRatio(1.9, contentMode: .fit).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).padding(.horizontal, 12)
            .accessibilityElement().accessibilityLabel(Text("8-Ball"))
            HStack(spacing: 10) {
                InfoTile(label: String(localized: "You have"), value: groupName(mine))
                InfoTile(label: String(localized: "Last shot"), value: lastNote)
            }
            .padding(.horizontal, 16)
            if match.done && v.how == "eight_early" { Text("The 8 went down before the group was cleared.").font(BFont.body(13)).foregroundStyle(Theme.mut).padding(.horizontal, 16) }
            Text("Touch the table, pull back and let go. The shot goes the other way.").font(BFont.body(12)).foregroundStyle(Theme.mut).multilineTextAlignment(.center).padding(.horizontal, 16)
        }
    }

    private var lastNote: String {
        guard let l = v.last else { return String(localized: "Break") }
        switch l.foul {
        case "scratch": return String(localized: "Foul: cue ball potted")
        case "no_hit": return String(localized: "Foul: nothing hit")
        case "wrong_ball": return String(localized: "Foul: wrong ball first")
        default: return l.again ? String(localized: "Potted one. Go again.") : String(localized: "\(l.potted.filter { $0 != 0 }.count) potted")
        }
    }

    private func table(rec: RecordingDTO?, frame: Int, canShoot: Bool) -> some View {
        GeometryReader { geo in
            let pad = geo.size.height * 0.06, w = geo.size.width - pad * 2, h = geo.size.height - pad * 2
            let p = { (x: Double, y: Double) in CGPoint(x: pad + x / 2 * w, y: pad + y * h) }
            let r = 0.03 / 2 * w
            Canvas { ctx, size in
                ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Surfaces.rail))
                ctx.fill(Path(roundedRect: CGRect(x: pad, y: pad, width: w, height: h), cornerRadius: 8), with: .color(Surfaces.felt))
                for (x, y) in [(0.0, 0.0), (1.0, 0.0), (2.0, 0.0), (0.0, 1.0), (1.0, 1.0), (2.0, 1.0)] { let o = p(x, y); ctx.fill(Path(ellipseIn: CGRect(x: o.x - r * 2.1, y: o.y - r * 2.1, width: r * 4.2, height: r * 4.2)), with: .color(.black)) }
                var positions = Dictionary(uniqueKeysWithValues: v.balls.map { ($0.id, ($0.x, $0.y)) })
                var hidden = Set(v.balls.filter(\.in).map(\.id))
                if let rec, frame < rec.frames.count - 1, let f = rec.frames[safe: frame] {
                    for (k, id) in rec.ids.enumerated() {
                        positions[id] = (f[k * 2], f[k * 2 + 1])
                        if let gone = rec.goneAt[String(id)] { if frame < gone { hidden.remove(id) } } else { hidden.remove(id) }
                    }
                }
                for b in v.balls where !hidden.contains(b.id) {
                    guard let pos = positions[b.id] else { continue }
                    let o = p(pos.0, pos.1), rect = CGRect(x: o.x - r, y: o.y - r, width: r * 2, height: r * 2)
                    if (9...15).contains(b.id) {
                        ctx.fill(Path(ellipseIn: rect), with: .color(.white))
                        var band = ctx; band.clip(to: Path(ellipseIn: rect)); band.fill(Path(CGRect(x: o.x - r, y: o.y - r * 0.5, width: r * 2, height: r)), with: .color(ballColor(b.id)))
                    } else { ctx.fill(Path(ellipseIn: rect), with: .color(ballColor(b.id))) }
                }
                if let a = aim, let cue = v.balls.first(where: { $0.id == 0 && !$0.in }) {
                    let from = p(cue.x, cue.y), len = w * (0.15 + a.power * 0.5)
                    ctx.stroke(Path { $0.move(to: from); $0.addLine(to: CGPoint(x: from.x + CGFloat(cos(a.angle)) * len, y: from.y + CGFloat(sin(a.angle)) * len)) }, with: .color(.white.opacity(0.7)), style: StrokeStyle(lineWidth: 3, dash: [10, 8]))
                }
            }
            .gesture(DragGesture()
                .onChanged { g in
                    // Pull back from where you touched: the shot goes the other way, further pulls hit harder.
                    let d = CGSize(width: -g.translation.width, height: -g.translation.height)
                    if hypot(d.width, d.height) > 8 { aim = (Double(atan2(d.height, d.width)), Double(min(1, hypot(d.width, d.height) / (geo.size.width * 0.45)))) }
                }
                .onEnded { _ in if let a = aim, canShoot { send(["angle": a.angle, "power": a.power]) }; aim = nil })
        }
    }
}
