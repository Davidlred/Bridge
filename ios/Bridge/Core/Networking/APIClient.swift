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
struct PlanDTO: Decodable, Sendable { let needsClarification: String?; let label: String; let goal: String; let feasibility: Feasibility; let tasks: [PlanTaskDTO] }
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
    struct Goal: Decodable, Sendable { let id: UUID; let label: String; let statement: String; let startDate: String; let deadline: String; let status: String; let sourceAllianceId: UUID?; let colorIndex: Int?; let updatedAt: Double?; let routine: String?; let realityNote: String?; let feasibility: Feasibility? }
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
    struct Settings: Decodable, Sendable { let coachReadsJournal: Bool? }
    let settings: Settings?
}

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
        return try JSONDecoder().decode(R.self, from: try await raw("POST", "/v1/ai/plan", body, authed: true)).plan
    }
    func reality(goal: String, deadline: String, today: String, routine: String, tasks: [String]) async throws -> Feasibility {
        struct R: Decodable { let feasibility: Feasibility }
        let body: [String: Any] = ["goalText": goal, "deadline": deadline, "today": today, "routine": routine, "tasks": Array(tasks.prefix(20).map { String($0.prefix(120)) })]
        return try JSONDecoder().decode(R.self, from: try await raw("POST", "/v1/ai/reality", body, authed: true)).feasibility
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

    private func raw(_ method: String, _ path: String, _ body: [String: Any]?, authed: Bool, upload: Data? = nil) async throws -> Data {
        let payload = try upload ?? body.map { try JSONSerialization.data(withJSONObject: $0) }
        let jpeg = upload != nil
        guard authed else { return try check(try await execute(method, path, payload, bearer: nil)) }
        guard let used = TokenStore.read() else { throw APIError.unauthorized }
        let first = try await execute(method, path, payload, bearer: used.access, jpeg: jpeg)
        guard first.1 == 401 else { return try check(first) }
        // Access token expired: refresh once (single flight), then retry.
        let fresh = try await refresh(replacing: used)
        let second = try await execute(method, path, payload, bearer: fresh.access, jpeg: jpeg)
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

    private func execute(_ method: String, _ path: String, _ body: Data?, bearer: String?, jpeg: Bool = false) async throws -> (Data, Int) {
        var req = URLRequest(url: URL(string: path, relativeTo: base)!)
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
