import BridgeCrypto
import XCTest

/// The Swift bindings against the Rust library (vodozemac) built for the simulator by crypto/build-ios.sh.
final class CryptoTests: XCTestCase {
    private let key = Data(repeating: 3, count: 32)

    func testTwoDevicesTalkThroughTheBindings() throws {
        let ana = OlmAccount(), bo = OlmAccount()
        let otk = try XCTUnwrap(bo.generateOneTimeKeys(count: 1).first)
        bo.markKeysAsPublished()
        let out = try ana.createOutboundSession(theirIdentityKey: bo.identityKeys().curve25519, theirOneTimeKey: otk.key)
        let first = out.encrypt(plaintext: "Run at 7?")
        XCTAssertEqual(first.msgType, 0)
        let inbound = try bo.createInboundSession(theirIdentityKey: ana.identityKeys().curve25519, body: first.body)
        XCTAssertEqual(inbound.plaintext, "Run at 7?")
        let reply = inbound.session.encrypt(plaintext: "Yes")
        // Ana's session survives a restart (pickled into the encrypted store)
        let restored = try OlmSession.fromPickle(pickle: out.pickle(key: key), key: key)
        XCTAssertEqual(try restored.decrypt(msgType: reply.msgType, body: reply.body), "Yes")
        XCTAssertThrowsError(try OlmAccount.fromPickle(pickle: ana.pickle(key: key), key: Data(count: 32)))
        let sn = safetyNumber(myEd25519: ana.identityKeys().ed25519, theirEd25519: bo.identityKeys().ed25519)
        XCTAssertEqual(sn, safetyNumber(myEd25519: bo.identityKeys().ed25519, theirEd25519: ana.identityKeys().ed25519))
        XCTAssertEqual(sn.count, 12)
    }
}
