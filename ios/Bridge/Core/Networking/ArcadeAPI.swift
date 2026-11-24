import Foundation

// MARK: Game Night Arcade: 1v1 matches, brackets and the Everyone games. The server runs every rule; these are its views.

struct PersonDTO: Codable, Equatable, Sendable, Hashable { let id: String; var name = "" }

/// The challenge card: the same for everyone, worded on each phone ("Your turn", "Sam's turn").
struct MatchCardDTO: Codable, Equatable, Sendable {
    let matchId: String; let game: String; var source = "chat_challenge"; var bracketId: String?
    var players: [PersonDTO] = []; var turn: String?; var status = "waiting"; var winnerId: String?; var result: String?
    var scores: [String: Int] = [:]; var scored = false; var toBeat: Int?; var held = false
}

/// Any JSON value, kept as-is (each game's view is decoded by its own screen).
enum JSONValue: Codable, Equatable, Sendable {
    case null, bool(Bool), number(Double), string(String), array([JSONValue]), object([String: JSONValue])
    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let b = try? c.decode(Bool.self) { self = .bool(b) }
        else if let n = try? c.decode(Double.self) { self = .number(n) }
        else if let s = try? c.decode(String.self) { self = .string(s) }
        else if let a = try? c.decode([JSONValue].self) { self = .array(a) }
        else { self = .object(try c.decode([String: JSONValue].self)) }
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .null: try c.encodeNil()
        case .bool(let b): try c.encode(b)
        case .number(let n): try c.encode(n)
        case .string(let s): try c.encode(s)
        case .array(let a): try c.encode(a)
        case .object(let o): try c.encode(o)
        }
    }
    func decode<T: Decodable>(_ type: T.Type) -> T? { (try? JSONEncoder().encode(self)).flatMap { try? JSONDecoder().decode(T.self, from: $0) } }
}

struct MatchDTO: Codable, Equatable, Sendable {
    let id: String; let game: String; var allianceId = ""; var source = "chat_challenge"; var bracketId: String?
    var players: [PersonDTO] = []; var turn: String?; var status = "waiting"; var winnerId: String?; var result: String?
    var scores: [String: Int] = [:]; var scored = false; var toBeat: Int?; var held = false
    var mode = "duel_turns"; var round: Int?; var version = 0; var you = ""; var known = true; var now: Double = 0
    var lastMoveAt: Double = 0; var canClaim = false; var view: JSONValue?

    var opponent: PersonDTO? { players.first { $0.id != you } }
    var done: Bool { status == "done" }
    var myTurn: Bool { !done && turn == you }
}

struct BracketMatchDTO: Codable, Equatable, Sendable, Identifiable {
    let id: String; var slot = 0; var a: PersonDTO?; var b: PersonDTO?; var status = "waiting"; var mode = "duel_live"
    var turn: String?; var winnerId: String?; var result: String?; var scores: [Int] = []; var mine = false
}
struct ByeDTO: Codable, Equatable, Sendable { var user: PersonDTO?; var vs: [PersonDTO?]? }
struct BracketRoundDTO: Codable, Equatable, Sendable, Identifiable { let round: Int; var label = "round"; var matches: [BracketMatchDTO] = []; var bye: ByeDTO?; var id: Int { round } }
struct BracketDTO: Codable, Equatable, Sendable {
    let id: String; var allianceId = ""; let game: String; var status = "active"; var round = 1; var roundCount = 1
    var deadline: Double = 0; var votes = 0; var voters = 0; var champion: PersonDTO?; var players: [PersonDTO?] = []
    var stages: [String] = []; var rounds: [BracketRoundDTO] = []; var myMatchId: String?; var playing = false
}

struct LivePlayerDTO: Codable, Equatable, Sendable, Identifiable { let userId: String; var name = ""; var ready = false; var absent = false; var watching = false; var id: String { userId } }
struct LiveResultDTO: Codable, Equatable, Sendable, Identifiable { let userId: String; var name = ""; var score = 0; var place = 1; var id: String { userId } }
struct LiveGameDTO: Codable, Equatable, Sendable {
    let id: String; let game: String; var allianceId = ""; var phase = "lobby"; var endsAt: Double = 0; var now: Double = 0; var max = 15; var min = 2
    var players: [LivePlayerDTO] = []; var world: JSONValue?; var results: [LiveResultDTO]?; var lowerWins = false
}
struct BracketRefDTO: Codable, Equatable, Sendable { let id: String; let game: String; var status = "active"; var championId: String? }
struct LiveRefDTO: Codable, Equatable, Sendable { let id: String; let game: String; var phase = "lobby" }

/// The games this version of the app can play. A match in any other game shows "Update The Bridge to play this".
let playableGames: Set<String> = ["four", "word", "scramble", "darts", "fleet", "cup", "pool", "hoops", "golf", "push", "drift", "whose"]

extension APIClient {
    private struct MatchR: Decodable { let match: MatchDTO }
    func match(_ id: String) async throws -> MatchDTO { try JSONDecoder().decode(MatchR.self, from: try await authedData("GET", "/v1/matches/\(id)")).match }
    func move(_ id: String, _ move: [String: Any]) async throws -> MatchDTO { try JSONDecoder().decode(MatchR.self, from: try await authedData("POST", "/v1/matches/\(id)/move", ["move": move])).match }
    func setMode(_ id: String, live: Bool) async throws -> MatchDTO { try JSONDecoder().decode(MatchR.self, from: try await authedData("POST", "/v1/matches/\(id)/mode", ["mode": live ? "duel_live" : "duel_turns"])).match }
    func resign(_ id: String) async throws -> MatchDTO { try JSONDecoder().decode(MatchR.self, from: try await authedData("POST", "/v1/matches/\(id)/resign")).match }
    func claim(_ id: String) async throws -> MatchDTO { try JSONDecoder().decode(MatchR.self, from: try await authedData("POST", "/v1/matches/\(id)/claim")).match }
    func rematch(_ id: String) async throws -> MatchDTO { try JSONDecoder().decode(MatchR.self, from: try await authedData("POST", "/v1/matches/\(id)/rematch")).match }
    func challenge(_ allianceId: String, game: String, opponentId: String) async throws -> MatchDTO {
        try JSONDecoder().decode(MatchR.self, from: try await authedData("POST", "/v1/alliances/\(allianceId)/challenges", ["game": game, "opponentId": opponentId])).match
    }
    func bracket(_ id: String) async throws -> BracketDTO {
        struct R: Decodable { let bracket: BracketDTO }
        return try JSONDecoder().decode(R.self, from: try await authedData("GET", "/v1/brackets/\(id)")).bracket
    }
    func live(_ allianceId: String) async throws -> LiveGameDTO? {
        struct R: Decodable { let game: LiveGameDTO? }
        return try JSONDecoder().decode(R.self, from: try await authedData("GET", "/v1/alliances/\(allianceId)/live")).game
    }
}

/// Decodes a socket frame's payload (already parsed by JSONSerialization).
func decodeFrame<T: Decodable>(_ raw: Any?, as type: T.Type) -> T? {
    guard let raw, let data = try? JSONSerialization.data(withJSONObject: raw) else { return nil }
    return try? JSONDecoder().decode(T.self, from: data)
}
