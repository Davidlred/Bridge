import BridgeCrypto
import Foundation
import Security
import SwiftData

/// 32 random bytes in the Keychain (this device only) that encrypt the Olm pickles.
enum OlmPickleKey {
    private static let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "app.thebridge", kSecAttrAccount as String: "olm-pickle"]
    static var value: Data {
        var q = query; q[kSecReturnData as String] = true; q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: AnyObject?
        if SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data, d.count == 32 { return d }
        var bytes = Data(count: 32)
        _ = bytes.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }
        var add = query; add[kSecValueData as String] = bytes; add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemDelete(query as CFDictionary); SecItemAdd(add as CFDictionary, nil)
        return bytes
    }
    /// Sign-out or account deletion: the pickles are gone, so is their key.
    static func reset() { SecItemDelete(query as CFDictionary) }
}

enum Verification { case unverified, verified, changed }

/// What goes inside the ciphertext. Versioned so later fields stay readable by older apps.
private struct DmPayload: Codable { var v = 1; let text: String; let sentAt: Double }

/**
 End-to-end encrypted direct messages with Olm (vodozemac via the BridgeCrypto package).

 This install has one Olm account; each device we talk to (the other person's devices and our own other devices) has
 its own session. Plaintext only exists on devices: the server relays public keys and ciphertext. Olm state is kept as
 pickles encrypted with a Keychain key.
 */
@MainActor @Observable
final class DmStore {
    /// Bumped when messages or peers change, so screens re-read.
    private(set) var version = 0
    private(set) var contacts: [DmContactDTO] = []

    @ObservationIgnored private var ctx: ModelContext { Persistence.container.mainContext }
    @ObservationIgnored private let api = APIClient.shared
    @ObservationIgnored private var account: OlmAccount?
    @ObservationIgnored private var tail: Task<Void, Never> = Task {}
    @ObservationIgnored private var key: Data { OlmPickleKey.value }
    private static let minKeys = 20, targetKeys = 50

    /// Olm state is single-writer: operations run one after another.
    private func serial(_ op: @escaping @MainActor () async -> Void) async {
        let previous = tail
        let next = Task { @MainActor in await previous.value; await op() }
        tail = next
        await next.value
    }

    private func me() -> String? {
        (UserDefaults.standard.data(forKey: "sessionUser").flatMap { try? JSONDecoder().decode(UserDTO.self, from: $0) })?.id.uuidString.lowercased()
    }

    // MARK: Reads
    func messages(_ peer: String) -> [DmMessageRow] {
        (try? ctx.fetch(FetchDescriptor<DmMessageRow>(predicate: #Predicate { $0.peerUserId == peer }, sortBy: [SortDescriptor(\.createdAt)]))) ?? []
    }
    func lastMessages() -> [String: DmMessageRow] {
        let all = (try? ctx.fetch(FetchDescriptor<DmMessageRow>(sortBy: [SortDescriptor(\.createdAt)]))) ?? []
        return Dictionary(all.map { ($0.peerUserId, $0) }, uniquingKeysWith: { _, b in b })
    }
    func unread(_ peer: String) -> Bool { messages(peer).contains { !$0.mine && !$0.read } }
    func peer(_ id: String) -> DmPeerRow? { try? ctx.fetch(FetchDescriptor<DmPeerRow>(predicate: #Predicate { $0.userId == id })).first }
    func verification(_ id: String) -> Verification {
        guard let p = peer(id), let v = p.verifiedKeys else { return .unverified }
        return v == p.currentKeys ? .verified : .changed
    }
    func markRead(_ peer: String) {
        for m in messages(peer) where !m.mine && !m.read { m.read = true }
        save()
    }
    private func save() { try? ctx.save(); version += 1 }

    func loadContacts() async {
        guard let list = try? await api.dmContacts() else { return }
        contacts = list
        for c in list { (peer(c.userId) ?? { let p = DmPeerRow(userId: c.userId, name: c.name); ctx.insert(p); return p }()).name = c.name }
        save()
    }

    // MARK: Account
    /// Loads or creates this install's account, registers it, and keeps one-time keys on the server.
    private func ready() async throws -> OlmAccount {
        if let account { return account }
        let row = try? ctx.fetch(FetchDescriptor<OlmAccountRow>()).first
        let acc = row.flatMap { try? OlmAccount.fromPickle(pickle: $0.pickle, key: key) } ?? OlmAccount()
        if row?.registered != true {
            let ids = acc.identityKeys()
            try await api.registerE2eDevice(curve25519: ids.curve25519, ed25519: ids.ed25519)
            try storeAccount(acc)
            try await topUp(acc, onServer: 0)
        }
        account = acc
        return acc
    }

    private func storeAccount(_ acc: OlmAccount) throws {
        let pickle = try acc.pickle(key: key)
        if let row = try? ctx.fetch(FetchDescriptor<OlmAccountRow>()).first { row.pickle = pickle; row.registered = true } else { ctx.insert(OlmAccountRow(pickle: pickle, registered: true)) }
        try? ctx.save()
    }

    private func topUp(_ acc: OlmAccount, onServer: Int) async throws {
        guard onServer < Self.minKeys else { return }
        let keys = acc.generateOneTimeKeys(count: UInt32(Self.targetKeys - onServer)).map { ($0.id, $0.key) }
        let fallback = acc.generateFallbackKey().map { ($0.id, $0.key) }
        _ = try await api.publishKeys(keys, fallback: fallback)
        acc.markKeysAsPublished()
        try storeAccount(acc)
    }

    // MARK: Sessions
    private func sessionRow(_ deviceId: String) -> OlmSessionRow? { try? ctx.fetch(FetchDescriptor<OlmSessionRow>(predicate: #Predicate { $0.deviceId == deviceId })).first }
    private func session(_ deviceId: String) -> OlmSession? { sessionRow(deviceId).flatMap { try? OlmSession.fromPickle(pickle: $0.pickle, key: key) } }
    private func keep(_ s: OlmSession, device: String, peer: String, curve: String, ed: String) {
        guard let pickle = try? s.pickle(key: key) else { return }
        if let row = sessionRow(device) { row.pickle = pickle; row.updatedAt = .now; if !curve.isEmpty { row.curve25519 = curve }; if !ed.isEmpty { row.ed25519 = ed } }
        else { ctx.insert(OlmSessionRow(deviceId: device, peerUserId: peer, curve25519: curve, ed25519: ed, pickle: pickle)) }
    }

    // MARK: Send
    /// Shows the message at once; it sends now, or stays queued until the connection is back.
    func send(to peer: String, _ text: String) async {
        let row = DmMessageRow(id: UUIDv7.next().uuidString.lowercased(), peerUserId: peer, mine: true, body: text.trimmingCharacters(in: .whitespacesAndNewlines), status: "queued", read: true)
        ctx.insert(row); save()
        await deliver(row.id)
    }
    func retry(_ id: String) async { await deliver(id) }
    func discard(_ id: String) { if let m = try? ctx.fetch(FetchDescriptor<DmMessageRow>(predicate: #Predicate { $0.id == id })).first { ctx.delete(m); save() } }
    func flushQueued() async {
        let ids = ((try? ctx.fetch(FetchDescriptor<DmMessageRow>(predicate: #Predicate { $0.status == "queued" }))) ?? []).map(\.id)
        for id in ids { await deliver(id) }
    }

    private func deliver(_ id: String) async {
        await serial { [self] in
            guard let m = try? ctx.fetch(FetchDescriptor<DmMessageRow>(predicate: #Predicate { $0.id == id })).first, let me = me() else { return }
            let peer = m.peerUserId, body = m.body, sentAt = m.createdAt.timeIntervalSince1970 * 1000
            do {
                let acc = try await ready()
                let theirs = try await api.userDevices(peer)
                guard !theirs.isEmpty else { m.status = "no_devices"; save(); return }
                let mine = try await api.userDevices(me).filter { $0.deviceId != DeviceID.value }
                recordKeys(peer, theirs)
                let targets = theirs.map { ($0, peer) } + mine.map { ($0, me) }
                let missing = targets.map(\.0.deviceId).filter { session($0) == nil }
                let claimed = missing.isEmpty ? [:] : Dictionary(try await api.claimKeys(missing).map { ($0.deviceId, $0) }, uniquingKeysWith: { a, _ in a })
                let payload = String(decoding: try JSONEncoder().encode(DmPayload(text: body, sentAt: sentAt)), as: UTF8.self)
                var envelopes: [(deviceId: String, type: Int, body: String)] = []
                for (d, owner) in targets {
                    guard let s = try session(d.deviceId) ?? claimed[d.deviceId].map({ try acc.createOutboundSession(theirIdentityKey: d.curve25519, theirOneTimeKey: $0.key) }) else { continue }
                    let e = s.encrypt(plaintext: payload)
                    keep(s, device: d.deviceId, peer: owner, curve: d.curve25519, ed: d.ed25519)
                    envelopes.append((d.deviceId, Int(e.msgType), e.body))
                }
                try await api.sendDm(to: peer, clientId: id, envelopes: envelopes)
                m.status = "sent"
            } catch APIError.offline {
                m.status = "queued"
            } catch {
                m.status = "failed"
            }
            save()
        }
    }

    // MARK: Receive
    /// Fetches, decrypts and stores what's waiting for this device, then tells the server to forget it.
    func receive() async {
        await serial { [self] in
            guard let me = me(), let acc = try? await ready(), let envelopes = try? await api.inbox(), !envelopes.isEmpty else { return }
            var done: [String] = []
            for e in envelopes {
                let text = await decrypt(acc, e, me: me)
                let parsed = text.flatMap { try? JSONDecoder().decode(DmPayload.self, from: Data($0.utf8)) }
                let cid = e.clientId
                if (try? ctx.fetch(FetchDescriptor<DmMessageRow>(predicate: #Predicate { $0.id == cid })).first) == nil {
                    let mine = e.from == me                         // a copy of something we sent from another device
                    ctx.insert(DmMessageRow(id: cid, peerUserId: e.peer, mine: mine, body: parsed?.text ?? text ?? "",
                                            createdAt: Date(timeIntervalSince1970: (parsed?.sentAt ?? e.createdAt) / 1000), status: text == nil ? "undecryptable" : "sent", read: mine))
                }
                done.append(e.id)
            }
            try? storeAccount(acc)                                  // one-time keys used up
            save()
            try? await api.ackInbox(done)
            if let left = try? await api.publishKeys([], fallback: nil) { try? await topUp(acc, onServer: left) }
        }
    }

    private func decrypt(_ acc: OlmAccount, _ e: EnvelopeDTO, me: String) async -> String? {
        let owner = e.from == me ? me : e.from
        if let s = session(e.fromDevice), let text = try? s.decrypt(msgType: UInt8(e.type), body: e.body) {
            keep(s, device: e.fromDevice, peer: owner, curve: "", ed: ""); return text
        }
        // A first message (or the other side started over): a new inbound session from the pre-key message.
        guard e.type == 0, let senderKey = e.senderKey, let inbound = try? acc.createInboundSession(theirIdentityKey: senderKey, body: e.body) else { return nil }
        let ed = (try? await api.userDevices(e.from))?.first { $0.deviceId == e.fromDevice }?.ed25519 ?? ""
        keep(inbound.session, device: e.fromDevice, peer: owner, curve: senderKey, ed: ed)
        return inbound.plaintext
    }

    // MARK: Safety numbers
    private func recordKeys(_ peer: String, _ devices: [DeviceKeysDTO]) {
        let keys = devices.map(\.ed25519).sorted().joined(separator: ",")
        let p = self.peer(peer) ?? { let p = DmPeerRow(userId: peer, name: ""); ctx.insert(p); return p }()
        if p.currentKeys != keys { p.currentKeys = keys; save() }
    }

    /// One safety number per device of theirs (usually one). Compare in person or on a call.
    func safetyNumbers(_ peer: String) async throws -> [[String]] {
        let mineKey = try await ready().identityKeys().ed25519
        let theirs = try await api.userDevices(peer)
        recordKeys(peer, theirs)
        return theirs.map { safetyNumber(myEd25519: mineKey, theirEd25519: $0.ed25519) }
    }
    func markVerified(_ peer: String) { if let p = self.peer(peer) { p.verifiedKeys = p.currentKeys; save() } }

    /// Report: the reporter's device sends the messages it decrypted (the server can't read DMs).
    func report(_ peer: String) async throws {
        try await api.reportUser(peer, reason: "dm", messages: messages(peer).filter { !$0.mine }.suffix(20).map(\.body))
    }
}
