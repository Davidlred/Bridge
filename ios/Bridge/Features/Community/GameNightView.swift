import SwiftUI

/// Game Night state on this device. The server runs the game; this sends intents and shows what the server says.
@MainActor @Observable
final class GameModel {
    var me: String?
    var game: GameDTO?
    /// Told only to this player by the server.
    var role: String?
    var revealed = false
    var ready = false
    var begun = false
    var choice: String?
    var voted = false
    var notice: String?
    var connected = true
    /// Moves made on this device during the round (the server only reports totals at the end).
    var mine = 0

    var isBuilder: Bool { role != "drifter" }
    func secondsLeft(_ now: Date) -> Int { max(0, Int(((game?.endsAt ?? 0) - now.timeIntervalSince1970 * 1000) / 1000)) }
    var myActions: Int { game?.phase == "result" ? (game?.players.first { $0.userId == me }?.actions ?? mine) : mine }
}

struct GameNightScreen: View {
    let allianceId: String
    @Environment(SocialStore.self) private var social
    @Environment(SessionStore.self) private var session
    @Environment(Toaster.self) private var toaster
    @Environment(\.dismiss) private var dismiss
    @State private var model = GameModel()

    var body: some View {
        GameNightContent(model: model, actions: .init(
            exit: { frame("game:leave"); dismiss() },
            ready: { model.ready = true; frame("game:ready") },
            reveal: { model.revealed = true },
            begin: { model.begun = true; frame("game:begin") },
            act: act,
            choose: { if !model.voted { model.choice = $0 } },
            lock: { guard let t = model.choice else { return }; model.voted = true; frame("game:vote", ["target": t]) },
            share: share,
            rematch: { Task { await open() } }
        ))
        .toolbar(.hidden, for: .navigationBar)
        .task {
            model.me = session.user?.id.uuidString.lowercased()
            let rt = social.realtime!
            rt.gameListener = { handle($0) }
            rt.onReady = { model.connected = true; frame("game:join") }        // first connect and every reconnect
            rt.acquire()
            await open()
        }
        .onDisappear { frame("game:leave"); social.realtime.gameListener = nil; social.realtime.onReady = nil; social.realtime.release() }
    }

    private func open() async {
        do {
            let g = try await social.openGame(allianceId)
            let me = model.me
            model = GameModel(); model.me = me; model.game = g
            frame("game:join")
        } catch { toaster.show(String(localized: "You're offline. Try again when you reconnect."), .warn) }
    }

    private func frame(_ type: String, _ extra: [String: Any] = [:]) {
        guard let id = model.game?.id else { return }
        social.realtime.send(extra.merging(["type": type, "gameId": id]) { a, _ in a })
    }

    private func act(_ plank: Int) {
        guard let p = model.game?.planks[safe: plank] else { return }
        if (model.isBuilder && p == "cracked") || (!model.isBuilder && p == "ok") { model.mine += 1 }
        frame("game:act", ["plank": plank])
    }

    private func handle(_ e: [String: Any]) {
        switch e["type"] as? String {
        case "game:state":
            guard let raw = e["game"], let data = try? JSONSerialization.data(withJSONObject: raw), let g = try? JSONDecoder().decode(GameDTO.self, from: data), g.id == model.game?.id else { return }
            model.game = g
        case "game:role":
            if e["gameId"] as? String == model.game?.id { model.role = e["role"] as? String }
        case "game:error":
            switch e["error"] as? String {
            case "game_over": model.notice = String(localized: "That game has ended.")
            case "already_started": model.notice = String(localized: "That game already started. You can join the next one.")
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

struct GameActions {
    var exit: () -> Void = {}, ready: () -> Void = {}, reveal: () -> Void = {}, begin: () -> Void = {}, act: (Int) -> Void = { _ in }
    var choose: (String) -> Void = { _ in }, lock: () -> Void = {}, share: () -> Void = {}, rematch: () -> Void = {}
}

struct GameNightContent: View {
    let model: GameModel
    var actions = GameActions()
    var now: Date?

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button(action: actions.exit) { Image(systemName: "xmark").font(.system(size: 20)).foregroundStyle(Theme.ink).frame(width: 44, height: 44) }.accessibilityLabel(Text("Leave game"))
                Text("GAME NIGHT · WHO'S DRIFTING?").bridgeLabel(10, color: Theme.acc).frame(maxWidth: .infinity)
                Color.clear.frame(width: 44, height: 44)
            }
            .padding(.leading, 10).padding(.trailing, 16).padding(.vertical, 4)
            TimelineView(.periodic(from: .now, by: 0.5)) { ctx in
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        if let n = model.notice { Text(n).font(BFont.body(14)).foregroundStyle(Theme.mut) }
                        if let g = model.game {
                            switch g.phase {
                            case "lobby": lobby(g, now ?? ctx.date)
                            case "role": roleCard
                            case "round": round(g, now ?? ctx.date)
                            case "vote": council(g)
                            case "result": result(g)
                            default:
                                Text("Not enough players were ready, so the lobby closed.").font(BFont.body(15)).foregroundStyle(Theme.mut)
                                Button("Done", action: actions.exit).buttonStyle(SecondaryButtonStyle())
                            }
                        }
                    }
                    .padding(.horizontal, 20).padding(.top, 8).padding(.bottom, 36)
                    .animation(.easeOut(duration: 0.2), value: model.game?.phase)
                }
            }
        }
        .background(Theme.s1)
    }

    @ViewBuilder private func lobby(_ g: GameDTO, _ now: Date) -> some View {
        Text(g.players.count >= 4 ? "Everyone showed up today." : "Practice round." as LocalizedStringKey).font(BFont.display(30)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
        Text("One of you is secretly the Drifter. Repair the bridge together, then vote out who you think it is. Takes about 2 minutes.").font(BFont.body(15)).foregroundStyle(Theme.mut)
        VStack(spacing: 8) {
            ForEach(g.players.sorted { a, b in a.userId == model.me && b.userId != model.me }, id: \.userId) { p in
                HStack(spacing: 12) {
                    Avatar(name: p.name, size: 34)
                    Text(p.userId == model.me ? String(localized: "You") : p.name).font(BFont.body(14, .medium)).foregroundStyle(Theme.ink).frame(maxWidth: .infinity, alignment: .leading)
                    if p.ready { Image(systemName: "checkmark.circle.fill").foregroundStyle(Theme.ok).accessibilityLabel(Text("Ready")) }
                    else { Text("Joining…").font(BFont.body(12)).foregroundStyle(Theme.mut) }
                }
                .padding(.horizontal, 14).padding(.vertical, 10)
                .background(Theme.s1, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(p.ready ? Theme.ok : Theme.line, lineWidth: 1))
                .accessibilityElement(children: .combine)
            }
        }
        Text("Starts when everyone in the lobby is ready (4 or more), or in \(model.secondsLeft(now)) s with at least 3.").font(BFont.body(12)).foregroundStyle(Theme.mut)
        if model.ready { waiting } else { Button("Get my role", action: actions.ready).buttonStyle(PrimaryButtonStyle()) }
    }

    private var waiting: some View {
        Text("Waiting for the others…").font(BFont.body(14, .medium)).foregroundStyle(Theme.ink2).frame(maxWidth: .infinity)
    }

    @ViewBuilder private var roleCard: some View {
        Text("KEEP YOUR SCREEN TO YOURSELF").bridgeLabel(11).frame(maxWidth: .infinity)
        if !model.revealed || model.role == nil {
            Button(action: actions.reveal) {
                VStack(spacing: 14) {
                    Image(systemName: "touchid").font(.system(size: 42)).foregroundStyle(Theme.acc)
                    Text("Tap to reveal your role").font(BFont.display(18)).foregroundStyle(Theme.ink)
                }
                .frame(maxWidth: .infinity, minHeight: 380)
                .background(Theme.bg, in: RoundedRectangle(cornerRadius: 28, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 28, style: .continuous).strokeBorder(Theme.accLine, lineWidth: 1))
            }
            .disabled(model.role == nil)
        } else {
            let tone = model.isBuilder ? Theme.ok : Theme.bad
            VStack(spacing: 14) {
                Text("YOU ARE THE").bridgeLabel(11)
                Text(model.isBuilder ? "Builder" : "Drifter" as LocalizedStringKey).font(BFont.display(46)).fontWeight(.bold).foregroundStyle(tone)
                Text(model.isBuilder ? "Repair cracked planks. Watch who is nearby when one breaks." : "Loosen planks without getting caught. Blend in at the council vote." as LocalizedStringKey)
                    .font(BFont.body(15)).foregroundStyle(Theme.ink2).multilineTextAlignment(.center)
            }
            .padding(28).frame(maxWidth: .infinity, minHeight: 380)
            .background(Theme.s1, in: RoundedRectangle(cornerRadius: 28, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 28, style: .continuous).strokeBorder(tone, lineWidth: 1))
            .accessibilityElement(children: .combine)
            if model.begun { waiting } else { Button("Start the round", action: actions.begin).buttonStyle(PrimaryButtonStyle()) }
        }
    }

    @ViewBuilder private func round(_ g: GameDTO, _ now: Date) -> some View {
        let left = model.secondsLeft(now)
        Text(model.isBuilder ? "Tap cracked planks to repair them" : "Tap solid planks to loosen them" as LocalizedStringKey).font(BFont.display(22)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
        HStack(spacing: 12) {
            Text(String(format: "0:%02d", left)).font(BFont.label(20)).fontWeight(.semibold).foregroundStyle(Theme.ink)
            Bar(fraction: Double(left) / 15, color: Theme.acc, height: 4)
        }
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 4), spacing: 8) {
            ForEach(Array(g.planks.enumerated()), id: \.offset) { i, p in
                let cracked = p == "cracked"
                Button { actions.act(i) } label: {
                    VStack(spacing: 4) {
                        if cracked { Image(systemName: "exclamationmark.triangle.fill").font(.system(size: 20)) }
                        Text("\(i + 1)").font(BFont.label(12)).fontWeight(.semibold)
                    }
                    .foregroundStyle(cracked ? Theme.warn : Theme.ink).frame(maxWidth: .infinity, minHeight: 74)
                    .background(Theme.s1, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).strokeBorder(cracked ? Theme.warn : Theme.ink, style: StrokeStyle(lineWidth: 1.5, dash: cracked ? [5, 4] : [])))
                }
                .accessibilityLabel(cracked ? Text("Plank \(i + 1), cracked") : Text("Plank \(i + 1), solid"))
            }
        }
        .padding(14).background(Theme.bg, in: RoundedRectangle(cornerRadius: 20, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(Theme.line, lineWidth: 1))
        HStack(spacing: 8) {
            stat("BRIDGE INTEGRITY", "\(g.integrity)%", g.integrity >= 75 ? Theme.ok : g.integrity >= 50 ? Theme.warn : Theme.bad)
            stat("YOU", model.isBuilder ? String(localized: "\(model.myActions) repaired") : String(localized: "\(model.myActions) loosened"), Theme.ink)
        }
        VStack(alignment: .leading, spacing: 6) {
            Text("SIGHTINGS").bridgeLabel(10)
            if g.clues.isEmpty { Text("Nothing seen yet.").font(BFont.body(13)).foregroundStyle(Theme.mut2) }
            ForEach(Array(g.clues.reversed().prefix(4).enumerated()), id: \.offset) { _, c in
                Text(c.text).font(BFont.body(13)).foregroundStyle(Theme.ink2).padding(.horizontal, 12).padding(.vertical, 8).frame(maxWidth: .infinity, alignment: .leading)
                    .background(Theme.bg, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            }
        }
    }

    private func stat(_ label: LocalizedStringKey, _ value: String, _ color: Color) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).bridgeLabel(9.5)
            Text(value).font(BFont.display(22)).foregroundStyle(color)
        }
        .padding(12).frame(maxWidth: .infinity, alignment: .leading).background(Theme.bg, in: RoundedRectangle(cornerRadius: 14, style: .continuous)).accessibilityElement(children: .combine)
    }

    @ViewBuilder private func council(_ g: GameDTO) -> some View {
        Text("The council meets").font(BFont.display(26)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
        Text("Who's been drifting? Check the sightings, then vote.").font(BFont.body(14)).foregroundStyle(Theme.mut)
        ForEach(g.players.filter { $0.userId != model.me }, id: \.userId) { p in
            let sel = model.choice == p.userId
            Button { actions.choose(p.userId) } label: {
                HStack(spacing: 12) {
                    Avatar(name: p.name, size: 40)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(p.absent ? "\(p.name) · \(String(localized: "Away"))" : p.name).font(BFont.body(15, .semibold)).foregroundStyle(p.absent ? Theme.mut : Theme.ink)
                        Text(g.clues.last { $0.who == p.userId }?.text ?? String(localized: "No sightings this round")).font(BFont.body(12)).foregroundStyle(Theme.mut).multilineTextAlignment(.leading)
                    }
                    Spacer(minLength: 0)
                    if sel { Image(systemName: "checkmark.circle.fill").font(.system(size: 22)).foregroundStyle(Theme.acc) }
                }
                .padding(.horizontal, 14).padding(.vertical, 12)
                .background(sel ? Theme.accTint : Theme.s1, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(sel ? Theme.acc : Theme.line, lineWidth: sel ? 2 : 1))
            }
            .disabled(p.absent || model.voted).accessibilityAddTraits(sel ? [.isSelected] : [])
        }
        if model.voted { Text("Vote locked. Waiting for the council…").font(BFont.body(14, .medium)).foregroundStyle(Theme.ink2).frame(maxWidth: .infinity) }
        else { Button("Lock in vote", action: actions.lock).buttonStyle(PrimaryButtonStyle()).disabled(model.choice == nil).opacity(model.choice == nil ? 0.5 : 1) }
    }

    @ViewBuilder private func result(_ g: GameDTO) -> some View {
        let iWasDrifter = g.drifterId == model.me
        let won = (g.buildersWon == true) != iWasDrifter
        let tone = won ? Theme.ok : Theme.bad
        let name = { (id: String?) in g.players.first { $0.userId == id }?.name ?? "" }
        let me = g.players.first { $0.userId == model.me }
        VStack(spacing: 10) {
            Text(won ? "You win" : "You lose" as LocalizedStringKey).font(BFont.display(44)).fontWeight(.bold).foregroundStyle(tone)
            Text(iWasDrifter ? String(localized: "You were the Drifter") : String(localized: "\(name(g.drifterId)) was the Drifter")).font(BFont.display(18)).foregroundStyle(Theme.ink).multilineTextAlignment(.center)
            Group {
                if g.ejectedId == nil && g.buildersWon == true { Text("The Drifter left the game.") }
                else if g.ejectedId == nil { Text("The council couldn't agree, so nobody was voted out.") }
                else if g.ejectedId == model.me { Text("The council voted you out.") }
                else { Text("The council voted out \(name(g.ejectedId)).") }
            }
            .font(BFont.body(14)).foregroundStyle(Theme.mut).multilineTextAlignment(.center)
        }
        .padding(.horizontal, 20).padding(.vertical, 28).frame(maxWidth: .infinity)
        .background(Theme.s1, in: RoundedRectangle(cornerRadius: 24, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(tone, lineWidth: 1))
        .accessibilityElement(children: .combine)
        if let tally = g.tally, !tally.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text("VOTES").bridgeLabel(10)
                ForEach(tally.sorted { $0.value > $1.value }, id: \.key) { id, n in
                    HStack(spacing: 10) {
                        Text(id == model.me ? String(localized: "You") : name(id)).font(BFont.body(13, .medium)).foregroundStyle(Theme.ink2).frame(width: 64, alignment: .leading).lineLimit(1)
                        Bar(fraction: Double(n) / Double(max(1, g.players.count)), color: id == g.drifterId ? Theme.bad : Theme.mut, height: 8)
                        Text("\(n)").font(BFont.label(12)).foregroundStyle(Theme.ink)
                    }
                    .accessibilityElement(children: .combine)
                }
            }
        }
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 4) {
                Text("ALLIANCE RATING").bridgeLabel(9.5)
                Text(g.ranked ? String(localized: "+\(me?.funPoints ?? 0) Fun") : String(localized: "Practice · no points")).font(BFont.display(g.ranked ? 20 : 15)).foregroundStyle(Theme.accInk)
            }
            .padding(14).frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.accTint, in: RoundedRectangle(cornerRadius: 14, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Theme.accLine, lineWidth: 1))
            VStack(alignment: .leading, spacing: 4) {
                Text("YOUR TITLE").bridgeLabel(9.5)
                Group { if g.mvpId == model.me { Text("MVP · most repairs") } else if won { Text("Sharp eye") } else { Text("Good sport") } }.font(BFont.display(16)).foregroundStyle(Theme.ink)
            }
            .padding(14).frame(maxWidth: .infinity, alignment: .leading).background(Theme.bg, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        Button(action: actions.share) { Label("Share to alliance chat", systemImage: "bubble.left.and.bubble.right.fill") }.buttonStyle(PrimaryButtonStyle())
        HStack(spacing: 8) {
            Button("Rematch", action: actions.rematch).buttonStyle(SecondaryButtonStyle())
            Button("Done", action: actions.exit).buttonStyle(SecondaryButtonStyle())
        }
    }
}

extension Array {
    subscript(safe i: Int) -> Element? { indices.contains(i) ? self[i] : nil }
}
