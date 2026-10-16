import Foundation
import SwiftData

/// Profile → Privacy → Community sharing. Streaks only by default.
enum ShareLevel: String, CaseIterable, Sendable {
    case nothing, streaks, all
    var next: ShareLevel { Self.allCases[(Self.allCases.firstIndex(of: self)! + 1) % Self.allCases.count] }
    var settingsLabel: String {
        switch self { case .nothing: String(localized: "Nothing"); case .streaks: String(localized: "Streaks only"); case .all: String(localized: "Streaks & milestones") }
    }
    var chipLabel: String {
        switch self { case .nothing: String(localized: "Private"); case .streaks: String(localized: "Streaks"); case .all: String(localized: "Streaks + wins") }
    }
}

enum Reaction: String, CaseIterable, Sendable { case respect, keepGoing, inspired
    var label: String { switch self { case .respect: String(localized: "Respect"); case .keepGoing: String(localized: "Keep going"); case .inspired: String(localized: "Inspired") } }
}

/// Alliance chat message as the views see it (a value copy of the stored row).
struct ChatLine: Identifiable, Equatable, Sendable {
    let id: String; let serverId: String?; let name: String?; let body: String; let mine: Bool; let createdAt: Date; let status: String
    /// No author and not ours: a system line, e.g. a shared game result.
    var system = false
}

/**
 Community, alliances and alliance chat. Social data lives on the server; the last copy is cached in the local store so
 screens open instantly and read offline. Chat written offline is queued and sent when the connection is back.
 */
@MainActor @Observable
final class SocialStore {
    enum SendResult { case sent, queued, failed, blocked }

    private(set) var communities: [UUID: CommunityDTO] = [:]
    private(set) var alliances: [AllianceDTO] = []
    /// Bumped whenever alliance chat rows change, so chat screens re-read.
    private(set) var chatVersion = 0
    var share: ShareLevel { ShareLevel(rawValue: shareRaw) ?? .streaks }
    private var shareRaw: String = UserDefaults.standard.string(forKey: "communityShare") ?? "streaks"

    @ObservationIgnored private var ctx: ModelContext { Persistence.container.mainContext }
    @ObservationIgnored private let api = APIClient.shared
    @ObservationIgnored private(set) var realtime: RealtimeClient!

    init() {
        alliances = cached("alliances", [AllianceDTO].self) ?? []
        realtime = RealtimeClient(store: self)
        NotificationCenter.default.addObserver(forName: .syncApplied, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.shareRaw = UserDefaults.standard.string(forKey: "communityShare") ?? "streaks" }
        }
    }

    // MARK: Cache
    private func cached<T: Decodable>(_ key: String, _ type: T.Type) -> T? {
        guard let row = try? ctx.fetch(FetchDescriptor<SocialCacheEntity>(predicate: #Predicate { $0.key == key })).first else { return nil }
        return try? JSONDecoder().decode(T.self, from: row.json)
    }
    private func store<T: Encodable>(_ key: String, _ value: T) {
        guard let data = try? JSONEncoder().encode(value) else { return }
        if let row = try? ctx.fetch(FetchDescriptor<SocialCacheEntity>(predicate: #Predicate { $0.key == key })).first { row.json = data; row.updatedAt = .now }
        else { ctx.insert(SocialCacheEntity(key: key, json: data)) }
        try? ctx.save()
    }

    // MARK: Sharing
    func cycleShare(userId: UUID?, goals: GoalStore) {
        let next = share.next
        shareRaw = next.rawValue
        UserDefaults.standard.set(next.rawValue, forKey: "communityShare")
        if let userId { goals.saveSetting(userId: userId, "share", next.rawValue) }
    }

    // MARK: Community
    func community(_ goalId: UUID) -> CommunityDTO? {
        // Read-only (views call this while rendering): falls back to the cached copy without storing it.
        communities[goalId] ?? cached("feed:\(goalId.uuidString)", CommunityDTO.self)
    }

    func refreshCommunity(_ goalId: UUID) async throws {
        let c = try await api.community(goalId: goalId)
        setCommunity(goalId, c)
    }

    private func setCommunity(_ goalId: UUID, _ c: CommunityDTO) {
        communities[goalId] = c
        store("feed:\(goalId.uuidString)", c)
    }

    private func flip(_ goalId: UUID, _ postId: String, _ r: Reaction, _ add: Bool) {
        guard var c = community(goalId), let i = c.posts.firstIndex(where: { $0.id == postId }) else { return }
        let d = add ? 1 : -1
        switch r {
        case .respect: c.posts[i].reactions.respect = max(0, c.posts[i].reactions.respect + d)
        case .keepGoing: c.posts[i].reactions.keepGoing = max(0, c.posts[i].reactions.keepGoing + d)
        case .inspired: c.posts[i].reactions.inspired = max(0, c.posts[i].reactions.inspired + d)
        }
        if add { c.posts[i].reacted.append(r.rawValue) } else { c.posts[i].reacted.removeAll { $0 == r.rawValue } }
        setCommunity(goalId, c)
    }

    /// Optimistic: the count changes at once and is put back if the server can't be reached.
    func react(_ goalId: UUID, _ post: PostDTO, _ r: Reaction) async throws {
        let on = !post.reacted.contains(r.rawValue)
        flip(goalId, post.id, r, on)
        do { try await api.react(postId: post.id, kind: r.rawValue, on: on) } catch { flip(goalId, post.id, r, !on); throw error }
    }

    /// Reported posts disappear for the reporter straight away.
    func report(_ goalId: UUID, _ post: PostDTO, reason: String) async throws {
        try await api.report(kind: "post", id: post.id, reason: reason)
        if var c = community(goalId) { c.posts.removeAll { $0.id == post.id }; setCommunity(goalId, c) }
    }

    func block(_ goalId: UUID, userId: String) async throws {
        try await api.block(userId: userId)
        if var c = community(goalId) { c.posts.removeAll { $0.userId == userId }; setCommunity(goalId, c) }
    }

    /// After a task is ticked: share a full day, a streak milestone or a step across the bridge. The server checks the
    /// claim against its completions and the sharing setting, and posts each one only once.
    func announce(goalId: UUID, store: GoalStore?) async {
        guard share != .nothing, let g = store?.goals.first(where: { $0.id == goalId }) else { return }
        let p = g.progress
        var claims: [(String, Int?)] = []
        if [7, 14, 21, 30, 50, 75, 100].contains(p.streak) || (p.streak > 100 && p.streak % 50 == 0) { claims.append(("streak", p.streak)) }
        if p.allDone { claims.append(("day_complete", nil)) }
        if let m = [25, 50, 75, 100].last(where: { Double(p.fraction) * 100 >= Double($0) }) { claims.append(("milestone", m)) }
        for (kind, value) in claims { try? await api.announce(goalId: goalId, kind: kind, value: value) }   // best effort
    }

    // MARK: Alliances
    func refreshAlliances() async throws { setAlliances(try await api.alliances()) }
    private func setAlliances(_ list: [AllianceDTO]) { alliances = list; store("alliances", list) }
    private func upsert(_ a: AllianceDTO) { setAlliances(alliances.filter { $0.id != a.id } + [a]) }

    func create(name: String, shared: Bool, goalId: UUID) async throws -> AllianceDTO {
        let a = try await api.createAlliance(name: name.trimmingCharacters(in: .whitespaces), planMode: shared ? "shared" : "own", goalId: goalId)
        upsert(a); realtime.resubscribe()
        return a
    }
    func preview(code: String) async throws -> InvitePreviewDTO { try await api.previewAlliance(code: code.uppercased()) }

    /// Joining a shared plan adds its goal and tasks; they arrive with the next sync.
    func join(code: String, goalId: UUID?) async throws -> AllianceDTO {
        let a = try await api.joinAlliance(code: code.uppercased(), goalId: goalId)
        upsert(a); realtime.resubscribe()
        await SyncEngine.shared.mutated()
        return a
    }
    func leave(_ a: AllianceDTO) async throws {
        try await api.leaveAlliance(a.id)
        setAlliances(alliances.filter { $0.id != a.id })
        await SyncEngine.shared.mutated()                          // a shared goal becomes a personal one
    }
    func applyNow(_ a: AllianceDTO) async throws { try await api.applyPlanNow(a.id) }

    // MARK: Alliance chat
    func lines(_ allianceId: String) -> [ChatLine] {
        let rows = (try? ctx.fetch(FetchDescriptor<AllianceMessageEntity>(predicate: #Predicate { $0.allianceId == allianceId }, sortBy: [SortDescriptor(\.createdAt)]))) ?? []
        return rows.map { ChatLine(id: $0.id, serverId: $0.serverId, name: $0.name, body: $0.body, mine: $0.mine, createdAt: $0.createdAt, status: $0.status, system: !$0.mine && $0.userId == nil && $0.serverId != nil) }
    }

    private func row(_ id: String) -> AllianceMessageEntity? { try? ctx.fetch(FetchDescriptor<AllianceMessageEntity>(predicate: #Predicate { $0.id == id })).first }

    /// Server copies replace the cache for one alliance; unsent messages are kept.
    func refreshMessages(_ allianceId: String) async throws {
        let list = try await api.allianceMessages(allianceId)
        let sent = (try? ctx.fetch(FetchDescriptor<AllianceMessageEntity>(predicate: #Predicate { $0.allianceId == allianceId && $0.status == "sent" }))) ?? []
        sent.forEach(ctx.delete)
        list.forEach { save($0, allianceId) }
        try? ctx.save(); chatVersion += 1
    }

    /// From the realtime socket or a refresh.
    func save(_ m: AllianceMessageDTO, _ allianceId: String) {
        let id = m.clientId ?? m.id
        let r = row(id) ?? { let e = AllianceMessageEntity(id: id, allianceId: allianceId, body: m.body, mine: m.mine); ctx.insert(e); return e }()
        r.serverId = m.id; r.userId = m.userId; r.name = m.name; r.body = m.body; r.mine = m.mine; r.status = "sent"
        r.createdAt = Date(timeIntervalSince1970: m.createdAt / 1000)
    }
    func received(_ m: AllianceMessageDTO, _ allianceId: String) { save(m, allianceId); try? ctx.save(); chatVersion += 1 }
    func hidden(serverId: String) {
        if let r = try? ctx.fetch(FetchDescriptor<AllianceMessageEntity>(predicate: #Predicate { $0.serverId == serverId })).first { ctx.delete(r); try? ctx.save(); chatVersion += 1 }
    }

    /// Shows the message at once; it sends now, or stays "queued" until the connection is back.
    @discardableResult
    func send(_ allianceId: String, _ body: String) async -> SendResult {
        let r = AllianceMessageEntity(id: UUIDv7.next().uuidString.lowercased(), allianceId: allianceId, body: body.trimmingCharacters(in: .whitespacesAndNewlines), mine: true, status: "queued")
        ctx.insert(r); try? ctx.save(); chatVersion += 1
        return await deliver(r.id)
    }
    func retry(_ id: String) async -> SendResult { row(id)?.status = "queued"; chatVersion += 1; return await deliver(id) }
    func discard(_ id: String) { if let r = row(id) { ctx.delete(r); try? ctx.save(); chatVersion += 1 } }

    /// Called when the network comes back.
    func flushQueued() async {
        let ids = ((try? ctx.fetch(FetchDescriptor<AllianceMessageEntity>(predicate: #Predicate { $0.status == "queued" }, sortBy: [SortDescriptor(\.createdAt)]))) ?? []).map(\.id)
        for id in ids { _ = await deliver(id) }
    }

    private func deliver(_ id: String) async -> SendResult {
        guard let r = row(id), let clientId = UUID(uuidString: id) else { return .failed }
        let allianceId = r.allianceId, body = r.body
        defer { try? ctx.save(); chatVersion += 1 }
        do {
            let sent = try await api.sendAllianceMessage(allianceId, clientId: clientId, body: body)
            row(id).map { $0.serverId = sent.id; $0.userId = sent.userId; $0.name = sent.name; $0.createdAt = Date(timeIntervalSince1970: sent.createdAt / 1000); $0.status = "sent" }
            return .sent
        } catch APIError.offline {
            return .queued
        } catch APIError.server(_, let code, _, _) {
            row(id)?.status = "failed"
            return code == "message_blocked" ? .blocked : .failed
        } catch {
            row(id)?.status = "failed"; return .failed
        }
    }

    func reportMessage(_ line: ChatLine) async throws {
        guard let serverId = line.serverId else { return }
        try await api.report(kind: "message", id: serverId, reason: "other")
        discard(line.id)
    }

    // MARK: Game Night, rankings, Hall of Fame
    func gameStatus(_ allianceId: String) async throws -> GameStatusDTO { try await api.gameStatus(allianceId) }
    func openGame(_ allianceId: String) async throws -> GameDTO { try await api.openGame(allianceId) }
    func nudge(_ allianceId: String) async throws -> Int { try await api.nudge(allianceId) }
    func shareGame(_ allianceId: String, gameId: String) async throws { try await api.shareGame(allianceId, gameId: gameId) }
    func rating(_ allianceId: String) async throws -> RatingDTO { try await api.rating(allianceId) }
    func rankings(_ allianceId: String) async throws -> BoardDTO { try await api.rankings(allianceId) }
    func badges() async throws -> BadgesDTO { try await api.badges() }

    /// Push registration for this install (no token until APNs is configured), plus the home time zone.
    func registerDevice() async { try? await api.registerDevice(pushToken: nil) }
}

/**
 One WebSocket while any chat screen is open (reference counted). The first frame authenticates; new alliance
 messages go into the local store, which the chat screen reads.
 */
@MainActor
final class RealtimeClient {
    private weak var store: SocialStore?
    private var task: URLSessionWebSocketTask?
    private var users = 0
    private var attempt = 0
    private var reconnect: Task<Void, Never>?
    /** Game frames (`game:state`, `game:role`, `game:error`) for the Game Night screen. */
    var gameListener: (([String: Any]) -> Void)?
    /** Called each time the server accepts the auth frame (first connect and every reconnect), so screens can re-join. */
    var onReady: (() -> Void)?
    private(set) var connected = false

    init(store: SocialStore) { self.store = store }

    func send(_ frame: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: frame) else { return }
        task?.send(.string(String(decoding: data, as: UTF8.self))) { _ in }
    }

    func acquire() { users += 1; if users == 1 { connect() } }
    func release() {
        users = max(0, users - 1)
        guard users == 0 else { return }
        reconnect?.cancel(); task?.cancel(with: .normalClosure, reason: nil); task = nil; connected = false
    }
    /// After joining or creating an alliance, so its messages arrive too.
    func resubscribe() { task?.send(.string(#"{"type":"subscribe"}"#)) { _ in } }

    private func connect() {
        guard let token = TokenStore.read()?.access else { return }
        var req = URLRequest(url: APIClient.shared.socketURL())
        req.setValue("ios", forHTTPHeaderField: "X-Platform")
        let t = URLSession.shared.webSocketTask(with: req)
        task = t
        t.resume()
        let auth = (try? JSONSerialization.data(withJSONObject: ["type": "auth", "token": token])).map { String(decoding: $0, as: UTF8.self) } ?? ""
        t.send(.string(auth)) { _ in }
        Task { await receive(t) }
    }

    private func receive(_ t: URLSessionWebSocketTask) async {
        while true {
            guard let msg = try? await t.receive() else { break }
            attempt = 0
            guard case .string(let text) = msg, let data = text.data(using: .utf8),
                  let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { continue }
            switch obj["type"] as? String {
            case "message":
                guard let allianceId = obj["allianceId"] as? String, let raw = obj["message"],
                      let m = try? JSONDecoder().decode(AllianceMessageDTO.self, from: JSONSerialization.data(withJSONObject: raw)) else { continue }
                store?.received(m, allianceId)
            case "hidden":
                if let id = obj["id"] as? String { store?.hidden(serverId: id) }             // removed by moderation
            case "ready":
                connected = true; onReady?()
            case let type? where type.hasPrefix("game:"):
                gameListener?(obj)
            default: break
            }
        }
        // Closed: 4401 means the access token expired; refresh and try again with backoff while a screen still needs it.
        guard task === t, users > 0 else { return }
        task = nil; connected = false
        let expired = t.closeCode.rawValue == 4401
        reconnect = Task {
            if expired { _ = try? await APIClient.shared.me() }
            try? await Task.sleep(for: .seconds(min(30, 1 << min(attempt, 5))))
            attempt += 1
            if !Task.isCancelled, users > 0, task == nil { connect() }
        }
    }
}
