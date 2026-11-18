import SwiftUI

/// "Pick tonight's game" on this device. The server runs the 30-second vote; this sends votes and shows the tally.
@MainActor @Observable
final class GamePickerModel {
    enum Filter: Hashable { case all, everyone, duel }
    var night: NightDTO?
    /// This member's vote, as the server last confirmed it (or the tap waiting for that).
    var mine: String?
    var filter: Filter = .all
    var notice: String?
    var connected = true
    /// How far the server's clock is ahead of this device's (ms).
    var offset: Double = 0

    func secondsLeft(_ now: Date) -> Int {
        guard let n = night else { return 0 }
        return min(99, max(0, Int(((n.endsAt - (now.timeIntervalSince1970 * 1000 + offset)) / 1000).rounded(.up))))
    }
    var shown: [NightGameDTO] {
        (night?.games ?? []).filter { g in
            switch filter { case .all: true; case .everyone: g.mode == "everyone"; case .duel: g.mode != "everyone" }
        }
    }
}

/// The route kind each game opens with (the Game Night screens predate the Arcade ids).
private func kindOf(_ game: String) -> String { game == "whose" ? "whose_day" : "drifting" }

struct GamePickerScreen: View {
    let allianceId: UUID
    @Environment(SocialStore.self) private var social
    @Environment(AppRouter.self) private var router
    @Environment(Toaster.self) private var toaster
    @Environment(\.dismiss) private var dismiss
    @State private var model = GamePickerModel()
    @State private var leaving = false

    private var id: String { allianceId.uuidString.lowercased() }

    var body: some View {
        GamePickerContent(model: model, exit: { dismiss() }, vote: vote, retry: { Task { await open() } })
            .toolbar(.hidden, for: .navigationBar)
            .task {
                let rt = social.realtime!
                rt.gameListener = { handle($0) }
                rt.onReady = { model.connected = true; join() }
                rt.acquire()
                await open()
            }
            .onDisappear { social.realtime.gameListener = nil; social.realtime.onReady = nil; social.realtime.release() }
    }

    private func open() async {
        model.notice = nil
        do {
            switch try await social.openNight(id) {
            case .night(let n):
                model.night = n; model.offset = n.now - Date().timeIntervalSince1970 * 1000
                join()
            case .running(let kind): go(kind, wait: false)
            case .needPlayers(let needs): model.notice = String(localized: "Game night needs at least \(needs) members in the alliance. Invite someone to play.")
            }
        } catch {
            model.connected = false
            model.notice = String(localized: "Game Night needs a connection.")
        }
    }

    private func join() {
        guard let n = model.night else { return }
        social.realtime.send(["type": "vote:join", "nightId": n.id])
    }

    /// Tap a tile to vote; tap it again to take the vote back.
    private func vote(_ game: String) {
        guard let n = model.night, n.phase == "voting" else { return }
        let next: String? = model.mine == game ? nil : game
        model.mine = next
        social.realtime.send(["type": "vote:cast", "nightId": n.id, "game": next ?? NSNull()])
    }

    /// The vote picked a game (or one was running): open it in place of the picker, so Back returns to Community.
    private func go(_ kind: String, wait: Bool) {
        guard !leaving else { return }
        leaving = true
        Task {
            if wait { try? await Task.sleep(for: .milliseconds(1200)) }      // leave the result up for a moment
            router.today.removeLast()
            router.today.append(kind == "whose_day" ? Route.whoseDay(allianceId: allianceId) : Route.gameNight(allianceId: allianceId))
        }
    }

    private func handle(_ e: [String: Any]) {
        switch e["type"] as? String {
        case "vote:state":
            guard let raw = e["night"], let data = try? JSONSerialization.data(withJSONObject: raw), let n = try? JSONDecoder().decode(NightDTO.self, from: data), n.id == model.night?.id else { return }
            model.night = n; model.offset = n.now - Date().timeIntervalSince1970 * 1000
            if n.phase == "decided", let chosen = n.chosen { go(kindOf(chosen), wait: true) }
        case "vote:you":
            guard e["nightId"] as? String == model.night?.id else { return }
            model.mine = e["game"] as? String
        case "vote:error":
            switch e["error"] as? String {
            case "closed": Task { await open() }                              // ended while away: a game is running now, or vote again
            case "not_votable": toaster.show(String(localized: "That game can't be played tonight."), .warn)
            default: break
            }
        default: break
        }
    }
}

/// Tile fills from the avatar palette, cycled in line-up order. Icons on them are always dark.
private let tileFills: [Color] = ([0xFCE7D9, 0xE4F2D9, 0xDFECF6, 0xF7EBCD, 0xEDE4F7] as [UInt32]).map { Color(light: $0, dark: $0) }
private let tileInk = Color(light: 0x23262B, dark: 0x23262B)

func gameName(_ id: String) -> String {
    switch id {
    case "drift": String(localized: "Who's Drifting?")
    case "whose": String(localized: "Whose Day?")
    case "word": String(localized: "Word Dash")
    case "scramble": String(localized: "Scramble")
    case "cup": String(localized: "Cup Pong")
    case "pool": String(localized: "8-Ball")
    case "hoops": String(localized: "Hoops")
    case "darts": String(localized: "Darts")
    case "golf": String(localized: "Mini Golf")
    case "four": String(localized: "Four in a Row")
    case "fleet": String(localized: "Fleet Battle")
    case "push": String(localized: "Push Out")
    default: String(localized: "A new game")
    }
}

/// Material Symbols names from the server, drawn with the closest SF Symbol.
func gameSymbol(_ icon: String) -> String {
    switch icon {
    case "visibility_off": "eye.slash.fill"
    case "person_search": "person.crop.circle.badge.questionmark"
    case "abc": "textformat"
    case "shuffle": "shuffle"
    case "local_drink": "cup.and.saucer.fill"
    case "radio_button_checked": "smallcircle.filled.circle"
    case "sports_basketball": "basketball.fill"
    case "adjust": "scope"
    case "golf_course": "figure.golf"
    case "view_column": "rectangle.split.3x1.fill"
    case "sailing": "sailboat.fill"
    case "trip_origin": "circle.circle"
    default: "gamecontroller.fill"
    }
}

struct GamePickerContent: View {
    let model: GamePickerModel
    var exit: () -> Void = {}
    var vote: (String) -> Void = { _ in }
    var retry: () -> Void = {}
    /// Fixed time for snapshots.
    var now: Date?

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button(action: exit) { Image(systemName: "xmark").font(.system(size: 20)).foregroundStyle(Theme.ink).frame(width: 44, height: 44) }.accessibilityLabel(Text("Leave game"))
                Text("GAME NIGHT").bridgeLabel(10, color: Theme.acc).frame(maxWidth: .infinity)
                Color.clear.frame(width: 44, height: 44)
            }
            .padding(.leading, 10).padding(.trailing, 16).padding(.vertical, 4)
            if !model.connected {
                Text("Reconnecting…").font(BFont.body(12, .medium)).foregroundStyle(Theme.warn).frame(maxWidth: .infinity).padding(8).background(Theme.warnTint)
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Pick tonight's game").font(BFont.display(28)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                        Text("Everyone votes. Most votes when the timer ends wins.").font(BFont.body(14)).foregroundStyle(Theme.mut)
                    }
                    if let notice = model.notice {
                        Text(notice).font(BFont.body(14)).foregroundStyle(Theme.mut)
                        Button("Retry", action: retry).buttonStyle(SecondaryButtonStyle())
                    }
                    if let n = model.night {
                        filters(total: n.games.count)
                        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10, alignment: .top), count: 3), spacing: 10) {
                            ForEach(model.shown) { g in
                                GameTile(game: g, votes: n.counts[g.id] ?? 0, mine: model.mine == g.id, enabled: n.phase == "voting") { vote(g.id) }
                            }
                        }
                    }
                }
                .padding(.horizontal, 20).padding(.top, 8).padding(.bottom, 24)
            }
            if let n = model.night { footer(n) }
        }
        .background(Theme.s1)
    }

    private func filters(total: Int) -> some View {
        @Bindable var model = model
        return HStack(spacing: 4) {
            ForEach([(GamePickerModel.Filter.all, String(localized: "All \(total)")), (.everyone, String(localized: "Everyone")), (.duel, String(localized: "1v1"))], id: \.0) { f, label in
                let on = model.filter == f
                Button { model.filter = f } label: {
                    Text(label).font(BFont.body(13, on ? .semibold : .medium)).foregroundStyle(on ? Theme.ink : Theme.mut).frame(maxWidth: .infinity, minHeight: 40)
                        .background(on ? Theme.s2 : .clear, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
                .buttonStyle(.plain).accessibilityAddTraits(on ? [.isSelected] : [])
            }
        }
        .padding(4).overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Theme.line, lineWidth: 1))
    }

    private func footer(_ n: NightDTO) -> some View {
        TimelineView(.periodic(from: .now, by: 0.25)) { ctx in
            let s = model.secondsLeft(now ?? ctx.date)
            VStack(spacing: 10) {
                HStack(spacing: 12) {
                    Text(String(format: "0:%02d", s)).font(BFont.label(13)).foregroundStyle(Theme.ink).monospacedDigit()
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Capsule().fill(Theme.track)
                            Capsule().fill(Theme.acc).frame(width: geo.size.width * (n.total > 0 ? CGFloat(n.voted) / CGFloat(n.total) : 0))
                        }
                    }
                    .frame(height: 6).accessibilityHidden(true)
                    Text("\(n.voted) of \(n.total) voted").font(BFont.body(12, .medium)).foregroundStyle(Theme.mut)
                }
                Text(banner(n)).font(BFont.body(14, .medium)).foregroundStyle(Theme.ink).frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 14).padding(.vertical, 12).background(Theme.accTint, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .accessibilityAddTraits(.updatesFrequently)
            }
            .padding(.horizontal, 20).padding(.top, 12).padding(.bottom, 20).background(Theme.s1)
        }
        .onChange(of: banner(n)) { _, text in AccessibilityNotification.Announcement(text).post() }   // lead, ties and the winner are read out
    }

    private func banner(_ n: NightDTO) -> String {
        if n.phase == "decided" {
            if let c = n.chosen { return String(localized: "\(gameName(c)) it is.") }
            return String(localized: "No game could start. Try again in a minute.")
        }
        if let t = n.tied { return String(localized: "Tied at \(t.votes). Arc breaks ties.") }
        if let l = n.leader { return l.votes == 1 ? String(localized: "\(gameName(l.game)) leads with 1 vote") : String(localized: "\(gameName(l.game)) leads with \(l.votes) votes") }
        return String(localized: "No votes yet. If nobody votes, Arc picks.")
    }
}

private struct GameTile: View {
    let game: NightGameDTO
    let votes: Int
    let mine: Bool
    let enabled: Bool
    var action: () -> Void

    private var tag: String {
        game.mode == "everyone" ? String(localized: "EVERYONE") : game.liveToo == true ? String(localized: "1V1") : String(localized: "1V1 · TURNS")
    }
    private var state: String? {
        if !game.built { return String(localized: "Soon") }
        if let needs = game.needs { return String(localized: "Needs \(needs)") }
        if !game.available { return String(localized: "Not available") }
        return nil
    }

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 8) {
                Image(systemName: gameSymbol(game.icon)).font(.system(size: 18, weight: .semibold)).foregroundStyle(tileInk).frame(width: 38, height: 38)
                    .background(tileFills[game.tile % tileFills.count], in: RoundedRectangle(cornerRadius: 11, style: .continuous))
                Text(gameName(game.id)).font(BFont.body(13, .semibold)).foregroundStyle(Theme.ink).lineLimit(2).multilineTextAlignment(.leading)
                Text(state?.uppercased() ?? tag).bridgeLabel(9).lineLimit(2)
            }
            .opacity(game.votable ? 1 : 0.45)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 10).padding(.vertical, 12)
            .background(mine ? Theme.accTint : Theme.s1, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(mine ? Theme.accLine : Theme.line, lineWidth: mine ? 1.5 : 1))
            .overlay(alignment: .topTrailing) {
                if votes > 0 {
                    Text("\(votes)").font(BFont.label(11)).foregroundStyle(mine ? Theme.s1 : Theme.ink).padding(.horizontal, 8).padding(.vertical, 2)
                        .background(mine ? Theme.ink : Theme.s2, in: Capsule()).padding(8)
                }
            }
        }
        .buttonStyle(.plain)
        .disabled(!(game.votable && enabled))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text([gameName(game.id), tag, state, votes > 0 ? (votes == 1 ? String(localized: "1 vote") : String(localized: "\(votes) votes")) : nil, mine ? String(localized: "your vote") : nil].compactMap { $0 }.joined(separator: ", ")))
        .accessibilityAddTraits(mine ? [.isButton, .isSelected] : .isButton)
        .accessibilityHint(Text(mine ? "Remove vote" : "Vote"))
    }
}
