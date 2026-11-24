import SwiftUI

// MARK: Game views, as the server sends them (each game's viewFor)

struct FourView: Decodable { var players: [String]; var board: [Int]; var next: Int; var moves: Int; var last: Int?; var winner: Int?; var draw: Bool; var line: [Int] }

struct FoundWord: Decodable, Hashable { let w: String; var pts: Int; var bonus: Bool? }
struct WordVerdict: Decodable { let w: String; var ok: Bool; var reason: String; var pts: Int; var bonus: Bool }
struct WordRound: Decodable { var started: Bool?; var endsAt: Double?; var ended: Bool; var score: Int; var count: Int?; var found: [FoundWord]; var last: WordVerdict? }
struct WordView: Decodable { var game: String; var letters: [String]; var seconds: Int; var players: [String]; var rounds: [String: WordRound] }

struct DartDTO: Decodable, Hashable { let x: Double; let y: Double; var score: Int; var mult: Int; var label: String }
struct DartsTurn: Decodable { let by: String; var darts: [DartDTO]; var bust: Bool }
struct DartsView: Decodable {
    var players: [String]; var remaining: [Int]; var next: Int; var darts: [DartDTO]; var turns: [Int]; var bust: Bool; var lastTurn: DartsTurn?
    var winner: Int?; var draw: Bool; var perks: [String]; var perkUsed: [String]
}

struct ShipDTO: Decodable, Hashable { let cells: [Int] }
struct ShotDTO: Decodable, Hashable { let cell: Int; let hit: Bool }
struct FleetMine: Decodable { var ships: [ShipDTO]?; var shots: [Int] }
struct FleetTheirs: Decodable { var shots: [ShotDTO]; var ships: [ShipDTO]; var left: Int }
struct FleetLast: Decodable { let by: String; let cell: Int; var hit: Bool; var sunk: [Int]? }
struct FleetView: Decodable { var players: [String]; var next: Int?; var winner: Int?; var last: FleetLast?; var size: Int; var placed: [String: Bool]; var mine: FleetMine; var theirs: FleetTheirs }

struct CupThrow: Decodable { let by: String; var cup: Int?; var rim: Bool; var path: [[Double]] }
struct CupView: Decodable {
    var players: [String]; var cups: [String: [Int]]; var spots: [String: [[Double]]]; var next: Int; var throwsLeft: Int; var turns: [Int]
    var last: CupThrow?; var winner: Int?; var draw: Bool; var perks: [String]; var perkUsed: [String]
}

struct RecordingDTO: Decodable, Equatable { var ids: [Int]; var frames: [[Double]]; var goneAt: [String: Int] }
struct BallDTO: Decodable { let id: Int; let x: Double; let y: Double; let `in`: Bool }
struct PoolShot: Decodable { let by: String; var rec: RecordingDTO; var potted: [Int]; var foul: String?; var again: Bool }
struct PoolView: Decodable { var players: [String]; var balls: [BallDTO]; var groups: [String: String?]; var next: Int; var shots: Int; var last: PoolShot?; var winner: Int?; var draw: Bool; var how: String? }

// MARK: Shared pieces

/// The avatar fills, cycled by player order and line-up order: discs, pucks, tiles. Ink on them is always dark.
let arcadeFills: [Color] = ([0xFCE7D9, 0xE4F2D9, 0xDFECF6, 0xF7EBCD, 0xEDE4F7] as [UInt32]).map { Color(light: $0, dark: $0) }
let arcadeInk = Color(light: 0x23262B, dark: 0x23262B)

/// Game surfaces (felt, ice, grass). Text never sits on them without its own backing.
enum Surfaces {
    static let tableTop = Color(light: 0x24564F, dark: 0x24564F)
    static let tableBottom = Color(light: 0x173A35, dark: 0x173A35)
    static let felt = Color(light: 0x1F5B45, dark: 0x1F5B45)
    static let rail = Color(light: 0x3B2A1E, dark: 0x3B2A1E)
    static let green = Color(light: 0x2F6B45, dark: 0x2F6B45)
    static let wall = Color(light: 0x6B4A2E, dark: 0x6B4A2E)
    static let ice = Color(light: 0xDDEBF3, dark: 0x1B2830)
    static let cup = Color(light: 0xD9473A, dark: 0xD9473A)
    static let court = Color(light: 0xC98B4F, dark: 0xC98B4F)
}

/// Plays a server recording (30 frames a second) once each time `key` changes. With Reduce Motion it shows the end.
struct Playback<Content: View>: View {
    let frames: Int
    let key: AnyHashable
    @ViewBuilder var content: (Int) -> Content
    @Environment(\.accessibilityReduceMotion) private var still
    @State private var start = Date.distantPast
    @State private var shownKey: AnyHashable?

    var body: some View {
        let last = max(0, frames - 1)
        TimelineView(.animation(paused: still || shownKey == key && Date().timeIntervalSince(start) * 30 >= Double(last))) { ctx in
            let f = still || shownKey != key ? last : min(last, Int(ctx.date.timeIntervalSince(start) * 30))
            content(f)
        }
        .onAppear { shownKey = key; start = .distantPast }
        .onChange(of: key) { _, k in shownKey = k; start = .now }
    }
}

struct ArcadeTopBar: View {
    let label: String
    var close: () -> Void
    var body: some View {
        HStack {
            Button(action: close) { Image(systemName: "xmark").font(.system(size: 20)).foregroundStyle(Theme.ink).frame(width: 44, height: 44) }.accessibilityLabel(Text("Leave game"))
            Text(label.uppercased()).bridgeLabel(10, color: Theme.acc).lineLimit(1).frame(maxWidth: .infinity)
            Color.clear.frame(width: 44, height: 44)
        }
        .padding(.horizontal, 10).padding(.vertical, 4)
    }
}

/// This player's own name in a match (for their avatar).
func myName(_ m: MatchDTO) -> String { m.players.first { $0.id == m.you }?.name ?? "" }

/// You · what's happening · them, across the top of every game. Turn changes are read out.
struct HeaderStrip: View {
    let me: String; let mine: String; let middle: String; let them: String; let theirs: String; let myTurn: Bool
    var body: some View {
        HStack(spacing: 8) {
            Avatar(name: me, size: 30, ink: Theme.ink)
            VStack(alignment: .leading, spacing: 1) {
                Text("YOU").bridgeLabel(9)
                Text(mine).font(BFont.display(17)).foregroundStyle(Theme.ink).lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Text(middle.uppercased()).bridgeLabel(9.5, color: myTurn ? Theme.acc : Theme.mut).multilineTextAlignment(.center).lineLimit(2).frame(maxWidth: .infinity)
            VStack(alignment: .trailing, spacing: 1) {
                Text(them.uppercased()).bridgeLabel(9).lineLimit(1)
                Text(theirs).font(BFont.display(17)).foregroundStyle(Theme.ink).lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .trailing)
            Avatar(name: them, size: 30, ink: Theme.ink)
        }
        .padding(.horizontal, 12).padding(.vertical, 10).background(Theme.s2, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .padding(.horizontal, 16)
        .accessibilityElement(children: .combine)
        .onChange(of: middle) { _, text in AccessibilityNotification.Announcement(text).post() }
    }
}

struct InfoTile: View {
    let label: String; let value: String
    var accent = false
    var action: (() -> Void)?
    var body: some View {
        let tile = VStack(alignment: .leading, spacing: 3) {
            Text(label.uppercased()).bridgeLabel(9)
            Text(value).font(BFont.body(13, .semibold)).foregroundStyle(Theme.ink).multilineTextAlignment(.leading)
        }
        .frame(maxWidth: .infinity, alignment: .leading).padding(12)
        .background(accent ? Theme.accTint : Theme.s2, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay { if accent { RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Theme.accLine, lineWidth: 1) } }
        if let action { Button(action: action) { tile }.buttonStyle(.plain) } else { tile.accessibilityElement(children: .combine) }
    }
}

func turnText(_ m: MatchDTO) -> String {
    let them = m.opponent?.name ?? ""
    if m.done { return String(localized: "Game over") }
    if m.myTurn { return String(localized: "Your turn") }
    if m.held { return String(localized: "Waiting for today's tasks") }
    if m.turn == nil { return String(localized: "Both setting up") }
    return String(localized: "\(them)'s turn")
}

func stageName(_ label: String) -> String {
    switch label {
    case "final": String(localized: "Final")
    case "semis": String(localized: "Semis")
    case "quarters": String(localized: "Quarters")
    default: String(localized: "Round")
    }
}
