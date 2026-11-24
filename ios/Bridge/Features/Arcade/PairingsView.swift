import SwiftUI

/// 1v1 Game Night: who plays whom, round by round, the bye, the stages and the Alliance champ.
struct PairingsScreen: View {
    let bracketId: String
    @Environment(SocialStore.self) private var social
    @Environment(SessionStore.self) private var session
    @Environment(AppRouter.self) private var router
    @Environment(Toaster.self) private var toaster
    @Environment(\.dismiss) private var dismiss
    @State private var bracket: BracketDTO?
    @State private var failed = false
    @State private var reload: Task<Void, Never>?

    var body: some View {
        PairingsContent(bracket: bracket, me: session.user?.id.uuidString.lowercased() ?? "", failed: failed, close: { dismiss() },
                        start: start, later: later, open: { router.today.append(Route.match($0)) }, retry: { Task { await load() } })
            .toolbar(.hidden, for: .navigationBar)
            .task {
                let rt = social.realtime!
                rt.gameListener = { handle($0) }
                rt.onReady = { Task { await load() } }
                rt.acquire()
                await load()
            }
            .onDisappear { social.realtime.gameListener = nil; social.realtime.onReady = nil; social.realtime.release() }
    }

    private func load() async {
        do { bracket = try await APIClient.shared.bracket(bracketId); failed = false } catch { failed = bracket == nil }
    }

    /// Bracket events and card changes reload it, at most a few times a second.
    private func handle(_ e: [String: Any]) {
        let type = e["type"] as? String ?? ""
        let ours = type.hasPrefix("bracket:") ? e["bracketId"] as? String == bracketId : type == "match:card" ? (e["match"] as? [String: Any])?["bracketId"] as? String == bracketId : false
        guard ours else { return }
        reload?.cancel()
        reload = Task { try? await Task.sleep(for: .milliseconds(300)); if !Task.isCancelled { await load() } }
    }

    private func start(_ matchId: String) {
        Task {
            do { _ = try await APIClient.shared.setMode(matchId, live: true); router.today.append(Route.match(matchId)) }
            catch { toaster.show(String(localized: "Game Night needs a connection."), .warn) }
        }
    }
    private func later(_ matchId: String) {
        Task {
            do { _ = try await APIClient.shared.setMode(matchId, live: false); toaster.show(String(localized: "Saved for later. You'll get a nudge when it's your turn."), .ok); await load() }
            catch { toaster.show(String(localized: "Game Night needs a connection."), .warn) }
        }
    }
}

struct PairingsContent: View {
    let bracket: BracketDTO?
    let me: String
    var failed = false
    var close: () -> Void = {}
    var start: (String) -> Void = { _ in }
    var later: (String) -> Void = { _ in }
    var open: (String) -> Void = { _ in }
    var retry: () -> Void = {}

    var body: some View {
        VStack(spacing: 0) {
            ArcadeTopBar(label: bracket.map { String(localized: "Game night · \(gameName($0.game))") } ?? String(localized: "Game night"), close: close)
            if let b = bracket {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        HStack(spacing: 12) {
                            VStack(alignment: .leading, spacing: 6) {
                                Text("TONIGHT · \(b.votes) OF \(b.voters) VOTES").bridgeLabel(10, color: Theme.acc)
                                Text(gameName(b.game)).font(BFont.display(30)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                                Text("It's 1v1, so Arc paired you up. Winners meet in the final.").font(BFont.body(14)).foregroundStyle(Theme.mut)
                            }
                            Spacer()
                            CoachMascot(mood: .happy, size: 72)
                        }
                        if let champ = b.champion { champion(champ, b) }
                        ForEach(Array(b.rounds.reversed())) { r in
                            VStack(alignment: .leading, spacing: 8) {
                                Text("ROUND \(r.round) · \(stageName(r.label).uppercased())").bridgeLabel(10)
                                ForEach(r.matches) { m in row(m, current: b.round == r.round && b.status == "active") }
                                if let bye = r.bye?.user {
                                    let vs = (r.bye?.vs ?? []).compactMap { $0 }
                                    Text(vs.count == 2 ? "Odd one out · \(bye.name) plays the winner of \(vs[0].name) vs \(vs[1].name)" : "\(bye.name) sits this round out")
                                        .font(BFont.body(13)).foregroundStyle(Theme.mut).padding(.horizontal, 14).padding(.vertical, 12).frame(maxWidth: .infinity, alignment: .leading)
                                        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Theme.line2, style: StrokeStyle(lineWidth: 1, dash: [6, 4])))
                                }
                            }
                        }
                        stages(b)
                        Text("The bracket ends at \(Date(timeIntervalSince1970: b.deadline / 1000).formatted(date: .omitted, time: .shortened)). A match nobody finished goes to whoever played more turns, otherwise Arc decides at random.")
                            .font(BFont.body(12)).foregroundStyle(Theme.mut)
                    }
                    .padding(.horizontal, 20).padding(.vertical, 8)
                }
                if b.status == "active", let mine = b.rounds.flatMap(\.matches).first(where: { $0.id == b.myMatchId }), mine.status != "done" {
                    VStack(spacing: 10) {
                        Button("Start my match") { start(mine.id) }.buttonStyle(PrimaryButtonStyle())
                        if mine.mode == "duel_live" { Button("Play later · I'll get a nudge") { later(mine.id) }.buttonStyle(SecondaryButtonStyle()) }
                    }
                    .padding(.horizontal, 20).padding(.top, 8).padding(.bottom, 20)
                }
            } else if failed {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Game Night needs a connection.").font(BFont.body(15)).foregroundStyle(Theme.ink)
                    Button("Retry", action: retry).buttonStyle(SecondaryButtonStyle())
                }
                .padding(24)
                Spacer()
            } else { Spacer() }
        }
        .background(Theme.s1)
    }

    @ViewBuilder private func row(_ m: BracketMatchDTO, current: Bool) -> some View {
        let winner = [m.a, m.b].compactMap { $0 }.first { $0.id == m.winnerId }
        let status: String = m.status == "done" ? (winner.map { String(localized: "Done · \($0.name)") } ?? String(localized: "Replay"))
            : m.mine && current ? String(localized: "Now") : m.status == "active" ? String(localized: "Playing") : String(localized: "Not started")
        let content = HStack(spacing: 8) {
            side(m.a, winner: m.winnerId, end: false)
            Text("VS").bridgeLabel(9)
            side(m.b, winner: m.winnerId, end: true)
            Text(status.uppercased()).bridgeLabel(9.5, color: m.mine && current && m.status != "done" ? Theme.acc : Theme.mut).lineLimit(2).padding(.leading, 4)
        }
        .padding(.horizontal, 12).padding(.vertical, 10).frame(minHeight: 56)
        .background(m.mine ? Theme.accTint : Theme.s2, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay { if m.mine { RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Theme.accLine, lineWidth: 1) } }
        if m.mine { Button { open(m.id) } label: { content }.buttonStyle(.plain).accessibilityHint(Text("Open the match")) }
        else { content.accessibilityElement(children: .combine) }
    }

    private func side(_ p: PersonDTO?, winner: String?, end: Bool) -> some View {
        HStack(spacing: 6) {
            if !end { Avatar(name: p?.name, size: 26, ink: Theme.ink) }
            Text(p?.name ?? "").font(BFont.body(14, p?.id == winner ? .bold : .medium)).foregroundStyle(Theme.ink).lineLimit(1)
            if end { Avatar(name: p?.name, size: 26, ink: Theme.ink) }
        }
        .frame(maxWidth: .infinity, alignment: end ? .trailing : .leading)
    }

    /// Semis → Final → Alliance champ, the current stage in bold.
    private func stages(_ b: BracketDTO) -> some View {
        HStack(spacing: 6) {
            ForEach(Array(b.stages.enumerated()), id: \.offset) { i, s in
                let on = b.status == "active" && i + 1 == b.round
                Text(stageName(s)).font(BFont.body(12, on ? .bold : .medium)).foregroundStyle(on ? Theme.ink : Theme.mut)
                Text("→").font(BFont.body(12)).foregroundStyle(Theme.mut2).accessibilityHidden(true)
            }
            Image(systemName: "trophy.fill").font(.system(size: 13)).foregroundStyle(b.status == "done" ? Theme.acc : Theme.mut).accessibilityHidden(true)
            Text("Alliance champ").font(BFont.body(12, b.status == "done" ? .bold : .medium)).foregroundStyle(b.status == "done" ? Theme.ink : Theme.mut)
        }
        .padding(.horizontal, 12).padding(.vertical, 10).frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.s2, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private func champion(_ p: PersonDTO, _ b: BracketDTO) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "trophy.fill").font(.system(size: 26)).foregroundStyle(Theme.acc).frame(width: 48, height: 48).background(Theme.s1, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            VStack(alignment: .leading, spacing: 3) {
                Text("ALLIANCE CHAMP").bridgeLabel(10)
                Text(p.name).font(BFont.display(22)).foregroundStyle(Theme.ink)
                Text("Won \(gameName(b.game)) night").font(BFont.body(12)).foregroundStyle(Theme.mut)
            }
            Spacer()
        }
        .padding(16).background(Theme.accTint, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(Theme.accLine, lineWidth: 1))
        .accessibilityElement(children: .combine)
        .onAppear { AccessibilityNotification.Announcement(String(localized: "\(p.name) is the Alliance champ")).post() }
    }
}
