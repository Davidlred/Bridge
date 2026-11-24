import SwiftUI

/// The 1v1 games with their icons, in line-up order (the chat's "Start a game" sheet).
let duelGames: [(id: String, icon: String, tile: Int)] = [("word", "abc", 2), ("scramble", "shuffle", 3), ("cup", "local_drink", 4), ("pool", "radio_button_checked", 0),
                                                         ("darts", "adjust", 2), ("four", "view_column", 4), ("fleet", "sailing", 0)]

func gameIconName(_ game: String) -> String {
    if let d = duelGames.first(where: { $0.id == game }) { return d.icon }
    switch game { case "hoops": return "sports_basketball"; case "golf": return "golf_course"; case "push": return "trip_origin"; case "drift": return "visibility_off"; case "whose": return "person_search"; default: return "" }
}

/// A challenge in the alliance chat: about 270pt wide with an accent border. The player whose turn it is gets Play and
/// Later; everyone else sees how it stands.
struct ChallengeCard: View {
    let card: MatchCardDTO
    let me: String
    var play: () -> Void = {}
    @State private var later = false

    var body: some View {
        let other = card.players.first { $0.id != me }
        let inIt = card.players.contains { $0.id == me }
        let turnName = card.players.first { $0.id == card.turn }?.name ?? ""
        let winner = card.players.first { $0.id == card.winnerId }
        let myTurn = card.status != "done" && card.turn == me
        let a = card.players[safe: 0]?.name ?? "", b = card.players[safe: 1]?.name ?? ""
        let status: String = {
            if card.status == "done", let w = winner {
                if card.scored && card.result == "played" { return String(localized: "\(w.name) won \(card.scores[w.id] ?? 0)–\(card.players.first { $0.id != w.id }.flatMap { card.scores[$0.id] } ?? 0)") }
                return String(localized: "\(w.name) won")
            }
            if card.status == "done" { return String(localized: "Draw") }
            if myTurn { return String(localized: "Your turn") }
            if card.turn == nil { return String(localized: "Setting up") }
            return String(localized: "\(turnName)'s turn")
        }()
        let title: String = {
            if card.status == "done" || !inIt { return String(localized: "\(a) vs \(b)") }
            if myTurn, let t = card.toBeat, let o = other { return String(localized: "\(o.name) scored \(t). Beat it?") }
            if myTurn { return String(localized: "Your move against \(other?.name ?? "")") }
            if card.held { return String(localized: "They'll see it when they're done today.") }
            return String(localized: "Waiting on \(turnName.isEmpty ? other?.name ?? "" : turnName)")
        }()
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                Image(systemName: gameSymbol(gameIconName(card.game))).font(.system(size: 22, weight: .semibold)).foregroundStyle(arcadeInk)
                Text(pattern).font(BFont.label(12)).foregroundStyle(arcadeInk)
                Spacer()
            }
            .padding(.horizontal, 14).frame(height: 64).background(arcadeFills[(duelGames.first { $0.id == card.game }?.tile ?? 1) % arcadeFills.count])
            VStack(alignment: .leading, spacing: 6) {
                Text("\(gameName(card.game)) · \(status)".uppercased()).bridgeLabel(9.5, color: myTurn ? Theme.acc : Theme.mut)
                Text(title).font(BFont.body(15, .semibold)).foregroundStyle(Theme.ink)
                if !playableGames.contains(card.game) {
                    Text("Update The Bridge to play this.").font(BFont.body(12)).foregroundStyle(Theme.mut)
                } else if myTurn && !later {
                    HStack(spacing: 8) {
                        Button(action: play) { Text("Play").font(BFont.body(14, .semibold)).foregroundStyle(Theme.onAcc).frame(maxWidth: .infinity, minHeight: 44).background(Theme.acc, in: RoundedRectangle(cornerRadius: 12, style: .continuous)) }
                        Button { later = true } label: {
                            Text("Later").font(BFont.body(14, .medium)).foregroundStyle(Theme.ink).frame(maxWidth: .infinity, minHeight: 44)
                                .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Theme.line, lineWidth: 1))
                        }
                    }
                    .buttonStyle(.plain).padding(.top, 4)
                }
            }
            .padding(14)
        }
        .frame(maxWidth: 270, alignment: .leading)
        .background(Theme.s1)
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Theme.accLine, lineWidth: 1.5))
        .contentShape(Rectangle())
        .onTapGesture { if inIt && !myTurn { play() } }
        .accessibilityElement(children: .contain)
    }

    /// Each game's preview strip: a pattern from the game.
    private var pattern: String {
        switch card.game {
        case "word": "S T A R  O N E W"
        case "scramble": "T R A I N S"
        case "four": "● ○ ● ○"
        case "darts": "T20 · D16"
        case "fleet": "A1 · J10"
        case "cup": "● ● ● ●"
        case "pool": "8"
        default: ""
        }
    }
}

/// "Start a game": pick a 1v1 game, then who to play.
struct ChallengeSheet: View {
    let members: [MemberDTO]
    var send: (String, String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var game: String?
    @State private var who: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Start a game").font(BFont.display(20)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                Text("GAME").bridgeLabel(10)
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 4), spacing: 8) {
                    ForEach(duelGames, id: \.id) { g in
                        let on = game == g.id
                        Button { game = g.id } label: {
                            VStack(spacing: 6) {
                                Image(systemName: gameSymbol(g.icon)).font(.system(size: 17, weight: .semibold)).foregroundStyle(arcadeInk).frame(width: 34, height: 34)
                                    .background(arcadeFills[g.tile % arcadeFills.count], in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                                Text(gameName(g.id)).font(BFont.body(11, .semibold)).foregroundStyle(Theme.ink).lineLimit(2).multilineTextAlignment(.center)
                            }
                            .frame(maxWidth: .infinity).padding(8)
                            .background(on ? Theme.accTint : Theme.s2, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                            .overlay { if on { RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Theme.accLine, lineWidth: 1.5) } }
                        }
                        .buttonStyle(.plain).accessibilityAddTraits(on ? .isSelected : [])
                    }
                }
                Text("WHO").bridgeLabel(10)
                ForEach(members.filter { !$0.me }, id: \.userId) { m in
                    let on = who == m.userId
                    Button { who = m.userId } label: {
                        HStack(spacing: 10) {
                            Avatar(name: m.name, size: 30, ink: Theme.ink)
                            Text(m.name).font(BFont.body(15, .medium)).foregroundStyle(Theme.ink)
                            Spacer()
                            if m.finished != true { Text("NOT DONE TODAY").bridgeLabel(9) }
                        }
                        .padding(.horizontal, 12).frame(minHeight: 52)
                        .background(on ? Theme.accTint : Theme.s2, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }
                    .buttonStyle(.plain).accessibilityAddTraits(on ? .isSelected : [])
                }
                Button("Send challenge") { if let game, let who { dismiss(); send(game, who) } }.buttonStyle(PrimaryButtonStyle()).disabled(game == nil || who == nil)
            }
            .padding(20)
        }
        .background(Theme.s1)
        .presentationDetents([.large])
    }
}
