package app.thebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import uniffi.bridge_crypto.CryptoException
import uniffi.bridge_crypto.OlmAccount
import uniffi.bridge_crypto.OlmSession
import uniffi.bridge_crypto.safetyNumber
import java.io.File

/**
 * The Kotlin bindings against a desktop build of the Rust library (`cargo build --release --lib` in crypto/).
 * Skipped when that build isn't there; the Rust tests in crypto/ cover the cryptography itself.
 */
class CryptoBindingsTest {
    companion object {
        @BeforeClass @JvmStatic fun library() {
            val dir = File("../../crypto/target/release").absoluteFile
            assumeTrue("build crypto/ for the host first", dir.listFiles()?.any { it.name.matches(Regex("(lib)?bridge_crypto\\.(dll|so|dylib)")) } == true)
            System.setProperty("jna.library.path", dir.path)
        }
    }

    private val key = ByteArray(32) { 3 }

    @Test fun twoDevicesTalkThroughTheBindings() {
        val ana = OlmAccount()
        val bo = OlmAccount()
        val otk = bo.generateOneTimeKeys(1u).first()
        bo.markKeysAsPublished()
        val out = ana.createOutboundSession(bo.identityKeys().curve25519, otk.key)
        val first = out.encrypt("""{"v":1,"text":"Run at 7?","sentAt":1}""")
        assertEquals(0, first.msgType.toInt())
        val inbound = bo.createInboundSession(ana.identityKeys().curve25519, first.body)
        assertEquals("""{"v":1,"text":"Run at 7?","sentAt":1}""", inbound.plaintext)
        val reply = inbound.session.encrypt("Yes")
        // Ana's session survives a restart (pickled into the encrypted database)
        val restored = OlmSession.fromPickle(out.pickle(key), key)
        assertEquals("Yes", restored.decrypt(reply.msgType, reply.body))
        assertThrows(CryptoException::class.java) { OlmAccount.fromPickle(ana.pickle(key), ByteArray(32)) }
        val sn = safetyNumber(ana.identityKeys().ed25519, bo.identityKeys().ed25519)
        assertEquals(sn, safetyNumber(bo.identityKeys().ed25519, ana.identityKeys().ed25519))
        assertEquals(12, sn.size)
    }
}
