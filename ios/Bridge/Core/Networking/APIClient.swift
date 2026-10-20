import Foundation
import Security

enum APIError: Error, Equatable {
    /// Refresh token rejected: the user has to sign in again. Local data and the outbox stay untouched.
    case unauthorized
    /// No connection, DNS failure, timeout.
    case offline
    /// The server answered with an error. `code` is the machine code from the body (e.g. `email_taken`).
    case server(status: Int, code: String, provider: String?, retryAfterSec: Int?)
}

struct Tokens: Equatable, Sendable { let access: String; let refresh: String }

struct UserDTO: Codable, Equatable, Sendable {
    let id: UUID
    var email: String?
    var displayName: String
    var coachName: String?
    var createdAt: Double?
    var photoUrl: String?
}
/// `isNew`: the account was just created, so the user goes to goal onboarding.
struct AuthResponse: Decodable, Sendable {
    let user: UserDTO; let accessToken: String; let refreshToken: String; let isNew: Bool
    private enum K: String, CodingKey { case user, accessToken, refreshToken, isNew }
    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: K.self)
        user = try c.decode(UserDTO.self, forKey: .user); accessToken = try c.decode(String.self, forKey: .accessToken)
        refreshToken = try c.decode(String.self, forKey: .refreshToken); isNew = try c.decodeIfPresent(Bool.self, forKey: .isNew) ?? false
    }
}

struct Suggestion: Codable, Equatable, Sendable { let title: String; var rationale: String = ""; var priority: String = "Medium"; var timeOfDay: String? }
enum CoachEvent: Sendable { case token(String), suggestion(Suggestion) }
enum CoachStreamError: Error { case interrupted }

struct Feasibility: Codable, Equatable, Sendable { let verdict: String; let reason: String; let suggestedDeadline: String? }
struct PlanTaskDTO: Decodable, Sendable { let title: String; let priority: String; let timeOfDay: String; let minutes: Int; let rationale: String }
struct PlanDTO: Decodable, Sendable { let needsClarification: String?; let label: String; let goal: String; let category: String?; let feasibility: Feasibility; let tasks: [PlanTaskDTO] }
struct GoalImageDTO: Decodable, Sendable { let kind: String; let status: String; let url: String?; let withFace: Bool? }

/// One outbox row on its way to the server. `patch` is the JSON object of changed fields.
struct Mutation: Sendable {
    let id: UUID; let entity: String; let entityId: UUID; let op: String; let patch: Data; let clientTs: Double
    init(_ m: OutboxEntity) { id = m.id; entity = m.entity; entityId = m.entityId; op = m.op; patch = m.patch; clientTs = (m.createdAt.timeIntervalSince1970 * 1000).rounded() }
}
struct PushResult: Decodable, Sendable { let accepted: Set<UUID>; let rejected: [UUID: String]
    private enum Keys: String, CodingKey { case accepted, rejected }
    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: Keys.self)
        accepted = try c.decodeIfPresent(Set<UUID>.self, forKey: .accepted) ?? []
        let raw = try c.decodeIfPresent([String: String].self, forKey: .rejected) ?? [:]
        rejected = Dictionary(uniqueKeysWithValues: raw.compactMap { k, v in UUID(uuidString: k).map { ($0, v) } })
    }
}
struct Changes: Decodable, Sendable {
    struct Goal: Decodable, Sendable { let id: UUID; let label: String; let statement: String; let startDate: String; let deadline: String; let status: String; let sourceAllianceId: UUID?; let colorIndex: Int?; let updatedAt: Double?; let routine: String?; let realityNote: String?; let feasibility: Feasibility?; let category: String? }
    struct Task: Decodable, Sendable { let id: UUID; let goalId: UUID; let title: String; let priority: String; let timeOfDay: String?; let minutes: Int?; let rationale: String?; let notes: String?; let origin: String; let sharedTaskId: UUID?; let position: Int; let deletedAt: Double?; let updatedAt: Double? }
    struct Completion: Decodable, Sendable { let taskId: UUID; let localDate: String; let completedAt: Double? }
    struct Removed: Decodable, Sendable { let goals: [UUID]; let completions: [Completion] }
    struct Journal: Decodable, Sendable { let id: UUID; let goalId: UUID?; let localDate: String; let mood: Int?; let body: String; let version: Int; let conflictOf: UUID? }
    let cursor: Int; let hasMore: Bool
    let goals: [Goal]; let tasks: [Task]; let completions: [Completion]; let removed: Removed
    let journal: [Journal]?; let removedJournal: [UUID]?
    struct Chat: Decodable, Sendable { let id: UUID; let conversation: UUID; let role: String; let text: String; let suggestion: Suggestion?; let status: String; let suggestionState: String?; let createdAt: Double }
    let chat: [Chat]?; let removedChat: [UUID]?
    /// Synced settings the app shows locally (unknown keys are ignored).
    struct Settings: Decodable, Sendable { let coachReadsJournal: Bool?; let share: String? }
    let settings: Settings?
}

// MARK: Social (milestone 4). Names are first names only; the server never sends other people's goal text or tasks.
struct ReactionCounts: Codable, Equatable, Sendable { var respect = 0; var keepGoing = 0; var inspired = 0 }
struct PostDTO: Codable, Equatable, Sendable, Identifiable {
    let id: String; var userId: String?; var mine = false; let name: String; let kind: String; var value: Int?; var createdAt: Double = 0
    var reactions = ReactionCounts(); var reacted: [String] = []
}
struct CommunityDTO: Codable, Equatable, Sendable { let unlocked: Bool; var streak = 0; var widened = false; var posts: [PostDTO] = [] }
struct MemberDTO: Codable, Equatable, Sendable { let userId: String; let name: String; let role: String; var away = false; var me = false }
struct DoneCount: Codable, Equatable, Sendable { var done = 0; var total = 0 }
struct SharedPlanDTO: Codable, Equatable, Sendable { let lead: String; let goal: String; let deadline: String; var me = DoneCount(); var group = DoneCount(); var isLead = false }
struct AllianceFeedDTO: Codable, Equatable, Sendable { var name: String?; let what: String; var title: String?; var at: Double = 0 }
struct AllianceDTO: Codable, Equatable, Sendable, Identifiable {
    let id: String; let name: String; let planMode: String; let inviteCode: String; var cap = 15; let startDate: String; var role: String?; var goalId: String?
    var streak = 0; var members: [MemberDTO] = []; var memberCount = 0; var shared: SharedPlanDTO?; var feed: [AllianceFeedDTO] = []
    var isShared: Bool { planMode == "shared" }
}
struct PreviewTaskDTO: Codable, Equatable, Sendable { let title: String; var priority = "Medium"; var timeOfDay: String? }
struct PaceDTO: Codable, Equatable, Sendable { let groupDay: Int; let groupTotal: Int; let yourTotal: Int }
struct InvitePreviewDTO: Codable, Equatable, Sendable {
    let id: String; let name: String; let planMode: String; let lead: String; let members: Int; let cap: Int; let spotsLeft: Int
    var goal: String?; var label: String?; var deadline: String?; var tasks: [PreviewTaskDTO] = []; var pace: PaceDTO?
}
struct AllianceMessageDTO: Codable, Equatable, Sendable { let id: String; var clientId: String?; var userId: String?; var name: String?; var mine = false; let body: String; var createdAt: Double = 0 }
private struct AllianceEnvelope: Decodable { let alliance: AllianceDTO }

// MARK: Encrypted DMs. The server relays public keys and ciphertext only.
struct DmContactDTO: Codable, Equatable, Sendable { let userId: String; let name: String; var ready = false }
struct DeviceKeysDTO: Codable, Equatable, Sendable { let deviceId: String; let curve25519: String; let ed25519: String }
struct ClaimedKeyDTO: Codable, Sendable { let deviceId: String; let keyId: String; let key: String; var fallback = false }
struct EnvelopeDTO: Codable, Sendable { let id: String; let from: String; let fromDevice: String; var senderKey: String?; let clientId: String; let type: Int; let body: String; let peer: String; var createdAt: Double = 0 }

// MARK: Game Night, rankings, Hall of Fame (milestone 5). Roles only appear in the result.
struct GamePlayerDTO: Codable, Equatable, Sendable {
    let userId: String; let name: String; var ready = false; var absent = false; var voted = false; var role: String?; var actions: Int?; var funPoints: Int?
    enum CodingKeys: String, CodingKey { case userId, name, ready, absent, voted, role, actions, funPoints = "fun" }
    init(userId: String, name: String, ready: Bool = false, absent: Bool = false, voted: Bool = false, role: String? = nil, actions: Int? = nil, funPoints: Int? = nil) {
        self.userId = userId; self.name = name; self.ready = ready; self.absent = absent; self.voted = voted; self.role = role; self.actions = actions; self.funPoints = funPoints
    }
    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        userId = try c.decode(String.self, forKey: .userId); name = try c.decode(String.self, forKey: .name)
        ready = try c.decodeIfPresent(Bool.self, forKey: .ready) ?? false; absent = try c.decodeIfPresent(Bool.self, forKey: .absent) ?? false
        voted = try c.decodeIfPresent(Bool.self, forKey: .voted) ?? false; role = try c.decodeIfPresent(String.self, forKey: .role)
        actions = try c.decodeIfPresent(Int.self, forKey: .actions); funPoints = try c.decodeIfPresent(Int.self, forKey: .funPoints)
    }
}
struct ClueDTO: Codable, Equatable, Sendable { let who: String; let text: String }
struct GameDTO: Codable, Equatable, Sendable {
    let id: String; let allianceId: String; var ranked = false; let phase: String; var endsAt: Double = 0; var players: [GamePlayerDTO] = []
    var planks: [String] = []; var integrity = 100; var clues: [ClueDTO] = []
    var drifterId: String?; var ejectedId: String?; var buildersWon: Bool?; var tally: [String: Int]?; var mvpId: String?
}
struct GameStatusDTO: Codable, Equatable, Sendable {
    var unlocked = false; var rankedToday = false; var eligible = 0; var finished = 0; var needsPlayers = false; var waiting: [String] = []; var meLeft: Int?
}
struct RatingDTO: Codable, Equatable, Sendable {
    let overall: Int; let tier: String; let rank: Int; let of: Int; var band = ""; let productivity: Int; let efficiency: Int; let funScore: Int
    enum CodingKeys: String, CodingKey { case overall, tier, rank, of, band, productivity, efficiency, funScore = "fun" }
}
struct BoardRowDTO: Codable, Equatable, Sendable { let rank: Int; let name: String; let score: Int; let tier: String; let members: Int; var mine = false }
struct BoardDTO: Codable, Equatable, Sendable { var category = "other"; var band = ""; var rank = 0; var of = 0; var rows: [BoardRowDTO] = [] }
struct BadgeDTO: Codable, Equatable, Sendable { let key: String; let earned: Bool; var earnedAt: Double?; var progress: Int?; var goal: Int? }
struct BadgesDTO: Codable, Equatable, Sendable { var `public` = false; var badges: [BadgeDTO] = [] }

/// All HTTP goes through here. Feature models call it; views never do.
actor APIClient {
    static let shared = APIClient()

    private let base = URL(string: Bundle.main.object(forInfoDictionaryKey: "APIBaseURL") as? String ?? "https://api.thebridge.app")!
    private let session: URLSession = { let c = URLSessionConfiguration.default; c.timeoutIntervalForRequest = 30; c.waitsForConnectivity = false; return URLSession(configuration: c) }()
    private var refreshing: Task<Tokens, Error>?
    private var timezone: String { TimeZone.current.identifier }

    // MARK: Auth
    func signUp(name: String, email: String, password: String) async throws -> AuthResponse {
        try await send("POST", "/v1/auth/email/signup", ["displayName": name, "email": email, "password": password, "timezone": timezone, "deviceId": DeviceID.value])
    }
    func logIn(email: String, password: String) async throws -> AuthResponse {
        try await send("POST", "/v1/auth/email/login", ["email": email, "password": password, "deviceId": DeviceID.value])
    }
    /// `nonce` is the raw value; Apple received its SHA-256 and the server checks both forms.
    func social(_ provider: String, idToken: String, nonce: String?, name: String?) async throws -> AuthResponse {
        var body: [String: Any] = ["idToken": idToken, "timezone": timezone, "deviceId": DeviceID.value]
        if let nonce { body["nonce"] = nonce }
        if let name, name.count >= 2 { body["displayName"] = name }
        return try await send("POST", "/v1/auth/\(provider)", body)
    }
    /// Coach name (empty or offensive names come back as "Arc") or display name.
    func updateMe(coachName: String? = nil, displayName: String? = nil) async throws -> UserDTO {
        struct R: Decodable { let user: UserDTO }
        var body: [String: Any] = [:]
        if let coachName { body["coachName"] = coachName }
        if let displayName { body["displayName"] = displayName }
        return try JSONDecoder().decode(R.self, from: try await raw("PATCH", "/v1/me", body, authed: true)).user
    }

    /// Streams a coach reply (Server-Sent Events). Calls `onEvent` for each token and the optional suggestion.
    /// Throws before the stream starts (offline, quota) or `CoachStreamError.interrupted` part way.
    func coach(_ payload: Data, onEvent: @escaping @MainActor @Sendable (CoachEvent) -> Void) async throws {
        func open(_ bearer: String) async throws -> (URLSession.AsyncBytes, HTTPURLResponse) {
            var req = URLRequest(url: URL(string: "/v1/coach/messages", relativeTo: base)!)
            req.httpMethod = "POST"; req.httpBody = payload; req.timeoutInterval = 60
            req.setValue("application/json", forHTTPHeaderField: "Content-Type"); req.setValue("text/event-stream", forHTTPHeaderField: "Accept")
            req.setValue("ios", forHTTPHeaderField: "X-Platform"); req.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
            do {
                let (bytes, res) = try await session.bytes(for: req)
                return (bytes, res as! HTTPURLResponse)
            } catch is URLError { throw APIError.offline }
        }
        guard let used = TokenStore.read() else { throw APIError.unauthorized }
        var (bytes, res) = try await open(used.access)
        if res.statusCode == 401 { _ = try await me(); (bytes, res) = try await open(TokenStore.read()!.access) }   // refreshes the token
        guard (200..<300).contains(res.statusCode) else {
            var data = Data(); for try await b in bytes { data.append(b) }
            _ = try check((data, res.statusCode)); return
        }
        var event = "", finished = false
        do {
            for try await line in bytes.lines {
                if line.hasPrefix("event: ") { event = String(line.dropFirst(7)) }
                else if line.hasPrefix("data: "), let data = line.dropFirst(6).data(using: .utf8) {
                    switch event {
                    case "token": if let t = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["text"] as? String { await onEvent(.token(t)) }
                    case "suggestion": if let s = try? JSONDecoder().decode(Suggestion.self, from: data) { await onEvent(.suggestion(s)) }
                    case "done": finished = true
                    case "error": throw CoachStreamError.interrupted
                    default: break
                    }
                }
            }
        } catch is URLError { throw CoachStreamError.interrupted }
        if !finished { throw CoachStreamError.interrupted }
    }

    func me() async throws -> UserDTO {
        struct R: Decodable { let user: UserDTO }
        return try JSONDecoder().decode(R.self, from: try await raw("GET", "/v1/me", nil, authed: true)).user
    }

    // MARK: AI (every model call happens on the server)
    func plan(goal: String, deadline: String, today: String, routine: String, existingGoals: Int) async throws -> PlanDTO {
        struct R: Decodable { let plan: PlanDTO }
        let body: [String: Any] = ["goalText": goal, "deadline": deadline, "today": today, "timezone": timezone, "routine": routine,
                                   "locale": Locale.current.identifier(.bcp47), "existingGoals": existingGoals]
        return try JSONDecoder().decode(R.self, from: try await raw("POST", "/v1/ai/plan", body, authed: true, timeout: 90)).plan
    }
    func reality(goal: String, deadline: String, today: String, routine: String, tasks: [String]) async throws -> Feasibility {
        struct R: Decodable { let feasibility: Feasibility }
        let body: [String: Any] = ["goalText": goal, "deadline": deadline, "today": today, "routine": routine, "tasks": Array(tasks.prefix(20).map { String($0.prefix(120)) })]
        return try JSONDecoder().decode(R.self, from: try await raw("POST", "/v1/ai/reality", body, authed: true, timeout: 90)).feasibility
    }
    func startImages(goalId: UUID, goal: String, routine: String, kinds: [String]) async throws -> [GoalImageDTO] {
        struct R: Decodable { let images: [GoalImageDTO] }
        let body: [String: Any] = ["goalId": goalId.uuidString.lowercased(), "goalText": goal, "routine": routine, "kinds": kinds]
        return try JSONDecoder().decode(R.self, from: try await raw("POST", "/v1/ai/images", body, authed: true)).images
    }
    func images(goalId: UUID) async throws -> [GoalImageDTO] {
        struct R: Decodable { let images: [GoalImageDTO] }
        return try JSONDecoder().decode(R.self, from: try await raw("GET", "/v1/ai/images/\(goalId.uuidString.lowercased())", nil, authed: true)).images
    }
    /// The caller has shown the consent text; the header records that the user agreed.
    func uploadPhoto(jpeg: Data) async throws -> String {
        struct R: Decodable { let photoUrl: String }
        return try JSONDecoder().decode(R.self, from: try await raw("PUT", "/v1/me/photo", nil, authed: true, upload: jpeg)).photoUrl
    }
    func deletePhoto() async throws { _ = try await raw("DELETE", "/v1/me/photo", nil, authed: true) }

    func forgotPassword(email: String) async throws { _ = try await raw("POST", "/v1/auth/password/forgot", ["email": email], authed: false) }
    func logOut() async throws { _ = try await raw("POST", "/v1/auth/logout", [:], authed: true) }
    func deleteAccount() async throws { _ = try await raw("DELETE", "/v1/account", nil, authed: true) }

    // MARK: Community and alliances
    func community(goalId: UUID) async throws -> CommunityDTO { try JSONDecoder().decode(CommunityDTO.self, from: try await raw("GET", "/v1/community?goalId=\(goalId.uuidString.lowercased())", nil, authed: true)) }
    /// Progress to share. The server checks it against its own completions and the sharing setting.
    func announce(goalId: UUID, kind: String, value: Int?) async throws {
        var body: [String: Any] = ["goalId": goalId.uuidString.lowercased(), "kind": kind]
        if let value { body["value"] = value }
        _ = try await raw("POST", "/v1/community/posts", body, authed: true)
    }
    func react(postId: String, kind: String, on: Bool) async throws { _ = try await raw(on ? "PUT" : "DELETE", "/v1/community/posts/\(postId)/reactions/\(kind)", nil, authed: true) }
    func report(kind: String, id: String, reason: String) async throws { _ = try await raw("POST", "/v1/reports", ["targetKind": kind, "targetId": id, "reason": reason], authed: true) }
    func block(userId: String) async throws { _ = try await raw("PUT", "/v1/blocks/\(userId)", nil, authed: true) }
    func alliances() async throws -> [AllianceDTO] {
        struct R: Decodable { let alliances: [AllianceDTO] }
        return try JSONDecoder().decode(R.self, from: try await raw("GET", "/v1/alliances", nil, authed: true)).alliances
    }
    func createAlliance(name: String, planMode: String, goalId: UUID) async throws -> AllianceDTO {
        try JSONDecoder().decode(AllianceEnvelope.self, from: try await raw("POST", "/v1/alliances", ["name": name, "planMode": planMode, "goalId": goalId.uuidString.lowercased()], authed: true)).alliance
    }
    func previewAlliance(code: String) async throws -> InvitePreviewDTO {
        struct R: Decodable { let preview: InvitePreviewDTO }
        return try JSONDecoder().decode(R.self, from: try await raw("GET", "/v1/alliances/preview/\(code)", nil, authed: true)).preview
    }
    func joinAlliance(code: String, goalId: UUID?) async throws -> AllianceDTO {
        var body: [String: Any] = ["code": code]
        if let goalId { body["goalId"] = goalId.uuidString.lowercased() }
        return try JSONDecoder().decode(AllianceEnvelope.self, from: try await raw("POST", "/v1/alliances/join", body, authed: true)).alliance
    }
    func leaveAlliance(_ id: String) async throws { _ = try await raw("POST", "/v1/alliances/\(id)/leave", nil, authed: true) }
    func applyPlanNow(_ id: String) async throws { _ = try await raw("POST", "/v1/alliances/\(id)/apply-now", nil, authed: true) }
    func allianceMessages(_ id: String) async throws -> [AllianceMessageDTO] {
        struct R: Decodable { let messages: [AllianceMessageDTO] }
        return try JSONDecoder().decode(R.self, from: try await raw("GET", "/v1/alliances/\(id)/messages", nil, authed: true)).messages
    }
    func sendAllianceMessage(_ id: String, clientId: UUID, body: String) async throws -> AllianceMessageDTO {
        struct R: Decodable { let message: AllianceMessageDTO }
        return try JSONDecoder().decode(R.self, from: try await raw("POST", "/v1/alliances/\(id)/messages", ["clientId": clientId.uuidString.lowercased(), "body": body], authed: true)).message
    }
    // MARK: Encrypted DMs
    func registerE2eDevice(curve25519: String, ed25519: String) async throws {
        _ = try await raw("PUT", "/v1/e2e/devices/\(DeviceID.value)", ["curve25519": curve25519, "ed25519": ed25519], authed: true)
    }
    /// Publishes one-time keys (and a fallback key); returns how many unused one-time keys the server holds.
    func publishKeys(_ keys: [(String, String)], fallback: (String, String)?) async throws -> Int {
        struct R: Decodable { let oneTimeKeys: Int }
        var body: [String: Any] = ["oneTimeKeys": keys.map { ["id": $0.0, "key": $0.1] }]
        if let f = fallback { body["fallback"] = ["id": f.0, "key": f.1] }
        return try JSONDecoder().decode(R.self, from: try await raw("POST", "/v1/e2e/devices/\(DeviceID.value)/keys", body, authed: true)).oneTimeKeys
    }
    func dmContacts() async throws -> [DmContactDTO] {
        struct R: Decodable { let contacts: [DmContactDTO] }
        return try JSONDecoder().decode(R.self, from: try await raw("GET", "/v1/e2e/contacts", nil, authed: true)).contacts
    }
    func userDevices(_ userId: String) async throws -> [DeviceKeysDTO] {
        struct R: Decodable { let devices: [DeviceKeysDTO] }
        return try JSONDecoder().decode(R.self, from: try await raw("GET", "/v1/e2e/users/\(userId)/devices", nil, authed: true)).devices
    }
    func claimKeys(_ deviceIds: [String]) async throws -> [ClaimedKeyDTO] {
        struct R: Decodable { let keys: [ClaimedKeyDTO] }
        return try JSONDecoder().decode(R.self, from: try await raw("POST", "/v1/e2e/claim", ["deviceIds": deviceIds], authed: true)).keys
    }
    func sendDm(to: String, clientId: String, envelopes: [(deviceId: String, type: Int, body: String)]) async throws {
        _ = try await raw("POST", "/v1/e2e/messages", ["to": to, "fromDevice": DeviceID.value, "clientId": clientId,
                                                       "envelopes": envelopes.map { ["deviceId": $0.deviceId, "type": $0.type, "body": $0.body] }], authed: true)
    }
    func inbox() async throws -> [EnvelopeDTO] {
        struct R: Decodable { let envelopes: [EnvelopeDTO] }
        return try JSONDecoder().decode(R.self, from: try await raw("GET", "/v1/e2e/inbox?deviceId=\(DeviceID.value)", nil, authed: true)).envelopes
    }
    func ackInbox(_ ids: [String]) async throws { _ = try await raw("POST", "/v1/e2e/inbox/ack", ["deviceId": DeviceID.value, "ids": ids], authed: true) }
    /// Report a person. For DMs, `messages` are what the reporter's device decrypted (the server can't read DMs).
    func reportUser(_ userId: String, reason: String, messages: [String]) async throws {
        _ = try await raw("POST", "/v1/reports", ["targetKind": "user", "targetId": userId, "reason": reason, "evidence": ["messages": messages.map { String($0.prefix(2000)) }]], authed: true)
    }

    // MARK: Game Night, rankings, Hall of Fame
    func gameStatus(_ allianceId: String) async throws -> GameStatusDTO { try JSONDecoder().decode(GameStatusDTO.self, from: try await raw("GET", "/v1/alliances/\(allianceId)/game", nil, authed: true)) }
    func openGame(_ allianceId: String) async throws -> GameDTO {
        struct R: Decodable { let game: GameDTO }
        return try JSONDecoder().decode(R.self, from: try await raw("POST", "/v1/alliances/\(allianceId)/game", nil, authed: true)).game
    }
    func nudge(_ allianceId: String) async throws -> Int {
        struct R: Decodable { let sent: Int }
        return try JSONDecoder().decode(R.self, from: try await raw("POST", "/v1/alliances/\(allianceId)/game/nudge", nil, authed: true)).sent
    }
    func shareGame(_ allianceId: String, gameId: String) async throws { _ = try await raw("POST", "/v1/alliances/\(allianceId)/games/\(gameId)/share", nil, authed: true) }
    func rating(_ allianceId: String) async throws -> RatingDTO { try JSONDecoder().decode(RatingDTO.self, from: try await raw("GET", "/v1/alliances/\(allianceId)/rating", nil, authed: true)) }
    func rankings(_ allianceId: String) async throws -> BoardDTO { try JSONDecoder().decode(BoardDTO.self, from: try await raw("GET", "/v1/alliances/\(allianceId)/rankings", nil, authed: true)) }
    /// The whole account as JSON ("Download my data"). Never logged or cached.
    func exportData() async throws -> Data { try await raw("GET", "/v1/me/export", nil, authed: true) }
    /// Entitlements the server honours (from RevenueCat webhooks).
    func entitlements() async throws -> [String] {
        struct E: Decodable { let id: String }
        struct R: Decodable { let entitlements: [E] }
        return try JSONDecoder().decode(R.self, from: try await raw("GET", "/v1/me/entitlements", nil, authed: true)).entitlements.map(\.id)
    }
    func badges() async throws -> BadgesDTO { try JSONDecoder().decode(BadgesDTO.self, from: try await raw("GET", "/v1/me/badges", nil, authed: true)) }

    /// This install, for push and the home time zone. `pushToken` is nil until APNs is configured.
    func registerDevice(pushToken: String?) async throws {
        _ = try await raw("PUT", "/v1/devices", ["id": DeviceID.value, "platform": "ios", "pushToken": pushToken ?? NSNull(), "timezone": timezone,
                                                 "locale": Locale.current.identifier(.bcp47), "appVersion": Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0.0"], authed: true)
    }
    /// The realtime socket. The token goes in the first frame, never the URL.
    nonisolated func socketURL() -> URL {
        var c = URLComponents(url: URL(string: "/v1/rt", relativeTo: base)!, resolvingAgainstBaseURL: true)!
        c.scheme = c.scheme == "https" ? "wss" : "ws"
        return c.url!
    }

    // MARK: Sync
    func push(_ batch: [Mutation]) async throws -> PushResult {
        let mutations: [[String: Any]] = try batch.map {
            ["id": $0.id.uuidString.lowercased(), "entity": $0.entity, "entityId": $0.entityId.uuidString.lowercased(), "op": $0.op,
             "patch": try JSONSerialization.jsonObject(with: $0.patch), "clientTs": Int($0.clientTs)]
        }
        return try JSONDecoder().decode(PushResult.self, from: try await raw("POST", "/v1/sync/push", ["mutations": mutations], authed: true))
    }
    func pull(since: Int) async throws -> Changes {
        try JSONDecoder().decode(Changes.self, from: try await raw("GET", "/v1/sync?since=\(since)", nil, authed: true))
    }

    // MARK: Plumbing
    private func send<T: Decodable>(_ method: String, _ path: String, _ body: [String: Any]) async throws -> T {
        try JSONDecoder().decode(T.self, from: try await raw(method, path, body, authed: false))
    }

    /// `timeout`: plan and Reality Check wait up to 90s (the server answers within 75s); everything else uses the session default.
    private func raw(_ method: String, _ path: String, _ body: [String: Any]?, authed: Bool, upload: Data? = nil, timeout: TimeInterval? = nil) async throws -> Data {
        let payload = try upload ?? body.map { try JSONSerialization.data(withJSONObject: $0) }
        let jpeg = upload != nil
        guard authed else { return try check(try await execute(method, path, payload, bearer: nil, timeout: timeout)) }
        guard let used = TokenStore.read() else { throw APIError.unauthorized }
        let first = try await execute(method, path, payload, bearer: used.access, jpeg: jpeg, timeout: timeout)
        guard first.1 == 401 else { return try check(first) }
        // Access token expired: refresh once (single flight), then retry.
        let fresh = try await refresh(replacing: used)
        let second = try await execute(method, path, payload, bearer: fresh.access, jpeg: jpeg, timeout: timeout)
        if second.1 == 401 { throw APIError.unauthorized }
        return try check(second)
    }

    private func refresh(replacing used: Tokens) async throws -> Tokens {
        if let current = TokenStore.read(), current != used { return current }      // another call already refreshed
        if let refreshing { return try await refreshing.value }
        let task = Task { [self] () throws -> Tokens in
            let payload = try JSONSerialization.data(withJSONObject: ["refreshToken": used.refresh])
            let r = try await execute("POST", "/v1/auth/refresh", payload, bearer: nil)
            if r.1 == 401 { throw APIError.unauthorized }
            struct R: Decodable { let accessToken: String; let refreshToken: String }
            let decoded = try JSONDecoder().decode(R.self, from: try check(r))
            let tokens = Tokens(access: decoded.accessToken, refresh: decoded.refreshToken)
            TokenStore.write(tokens)
            return tokens
        }
        refreshing = task
        defer { refreshing = nil }
        return try await task.value
    }

    private func check(_ r: (Data, Int)) throws -> Data {
        if (200..<300).contains(r.1) { return r.0 }
        if r.1 == 426 { Task { @MainActor in NotificationCenter.default.post(name: .upgradeRequired, object: nil) } }
        let obj = (try? JSONSerialization.jsonObject(with: r.0)) as? [String: Any]
        throw APIError.server(status: r.1, code: obj?["error"] as? String ?? "http_\(r.1)", provider: obj?["provider"] as? String, retryAfterSec: obj?["retryAfterSec"] as? Int)
    }

    private func execute(_ method: String, _ path: String, _ body: Data?, bearer: String?, jpeg: Bool = false, timeout: TimeInterval? = nil) async throws -> (Data, Int) {
        var req = URLRequest(url: URL(string: path, relativeTo: base)!)
        if let timeout { req.timeoutInterval = timeout }
        req.httpMethod = method
        req.httpBody = body
        if jpeg { req.setValue("image/jpeg", forHTTPHeaderField: "Content-Type"); req.setValue("1", forHTTPHeaderField: "X-Photo-Consent") }
        else if body != nil { req.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        req.setValue("ios", forHTTPHeaderField: "X-Platform")
        req.setValue(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0.0", forHTTPHeaderField: "X-App-Version")
        if let bearer { req.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization") }
        do {
            let (data, response) = try await session.data(for: req)
            return (data, (response as? HTTPURLResponse)?.statusCode ?? 0)
        } catch is URLError {
            throw APIError.offline
        }
    }
}

extension Notification.Name { static let upgradeRequired = Notification.Name("upgradeRequired") }

/// Stable per-install id sent with sign-in so the server can tie a session to a device.
enum DeviceID {
    static var value: String {
        if let v = UserDefaults.standard.string(forKey: "deviceId") { return v }
        let v = UUIDv7.next().uuidString.lowercased()
        UserDefaults.standard.set(v, forKey: "deviceId")
        return v
    }
}

/// Access + refresh token in the Keychain. `afterFirstUnlockThisDeviceOnly` so background sync can read them and they never leave the device.
enum TokenStore {
    private static let account = "tokens"
    private static var query: [String: Any] { [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "app.thebridge", kSecAttrAccount as String: account] }

    static func read() -> Tokens? {
        var q = query; q[kSecReturnData as String] = true; q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        let parts = String(decoding: d, as: UTF8.self).split(separator: "\n", maxSplits: 1).map(String.init)
        return parts.count == 2 ? Tokens(access: parts[0], refresh: parts[1]) : nil
    }

    static func write(_ tokens: Tokens?) {
        SecItemDelete(query as CFDictionary)
        guard let tokens else { return }
        var q = query
        q[kSecValueData as String] = Data("\(tokens.access)\n\(tokens.refresh)".utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(q as CFDictionary, nil)
    }
}
