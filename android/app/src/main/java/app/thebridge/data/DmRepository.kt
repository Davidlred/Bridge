package app.thebridge.data

import android.content.Context
import app.thebridge.data.local.BridgeDb
import app.thebridge.data.local.DatabaseKey
import app.thebridge.data.local.DmMessageEntity
import app.thebridge.data.local.DmPeerEntity
import app.thebridge.data.local.OlmAccountEntity
import app.thebridge.data.local.OlmSessionEntity
import app.thebridge.data.remote.ApiClient
import app.thebridge.data.remote.ApiException
import app.thebridge.data.remote.DeviceKeysDto
import app.thebridge.data.remote.DmContactDto
import app.thebridge.data.remote.EnvelopeDto
import app.thebridge.data.remote.EnvelopeOutDto
import app.thebridge.data.remote.OfflineException
import app.thebridge.data.session.SessionManager
import app.thebridge.util.UuidV7
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import uniffi.bridge_crypto.OlmAccount
import uniffi.bridge_crypto.OlmSession
import uniffi.bridge_crypto.safetyNumber
import javax.inject.Inject
import javax.inject.Singleton

/** What goes inside the ciphertext. Versioned so later fields (photos, replies) stay readable by older apps. */
@Serializable private data class DmPayload(val v: Int = 1, val text: String, val sentAt: Long)

/**
 * End-to-end encrypted direct messages with Olm (vodozemac via the bridge_crypto Rust library).
 *
 * This install has one Olm account; each device we talk to (the other person's devices and our own other devices)
 * has its own session. Plaintext only ever exists on devices: the server relays public keys and ciphertext.
 * All Olm state is kept as pickles in the SQLCipher database, encrypted again with a key derived from its passphrase.
 */
@Singleton
class DmRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val db: BridgeDb,
    private val api: ApiClient,
    private val session: SessionManager,
) {
    private val dao get() = db.dm()
    private val lock = Mutex()                      // Olm state is single-writer
    private val key by lazy { DatabaseKey.olmPickleKey(ctx) }
    private var account: OlmAccount? = null

    fun threads(): Flow<List<DmMessageEntity>> = dao.all()
    fun thread(peer: String): Flow<List<DmMessageEntity>> = dao.thread(peer)
    fun peers(): Flow<List<DmPeerEntity>> = dao.peers()
    suspend fun contacts(): List<DmContactDto> = api.dmContacts().also { list -> list.forEach { c -> dao.savePeer((dao.peer(c.userId) ?: DmPeerEntity(c.userId, c.name)).copy(name = c.name)) } }
    suspend fun markRead(peer: String) = dao.markRead(peer)

    private suspend fun me(): String = session.user.value?.id ?: throw IllegalStateException("signed out")

    /** Loads or creates this install's account, registers it, and keeps at least [MIN_KEYS] one-time keys on the server. */
    private suspend fun ready(): OlmAccount {
        account?.let { return it }
        val row = dao.account()
        val acc = row?.let { runCatching { OlmAccount.fromPickle(it.pickle, key) }.getOrNull() } ?: OlmAccount()
        val device = session.deviceId()
        if (row?.registered != true) {
            val ids = acc.identityKeys()
            api.registerE2eDevice(device, ids.curve25519, ids.ed25519)
            dao.saveAccount(OlmAccountEntity(pickle = acc.pickle(key), registered = true))
            topUp(acc, device, 0)
        }
        account = acc
        return acc
    }

    private suspend fun topUp(acc: OlmAccount, device: String, onServer: Int) {
        if (onServer >= MIN_KEYS) return
        val keys = acc.generateOneTimeKeys((TARGET_KEYS - onServer).toUInt()).map { it.id to it.key }
        val fallback = acc.generateFallbackKey()?.let { it.id to it.key }
        api.publishKeys(device, keys, fallback)
        acc.markKeysAsPublished()
        dao.saveAccount(OlmAccountEntity(pickle = acc.pickle(key), registered = true))
    }

    /** Called after receiving: tops up one-time keys if the server is running low. */
    private suspend fun maintainKeys(acc: OlmAccount) {
        val device = session.deviceId()
        topUp(acc, device, api.publishKeys(device, emptyList(), null))
    }

    private suspend fun storedSession(deviceId: String): OlmSession? =
        dao.session(deviceId)?.let { runCatching { OlmSession.fromPickle(it.pickle, key) }.getOrNull() }

    private suspend fun keep(deviceId: String, peer: String, d: DeviceKeysDto?, s: OlmSession) {
        val old = dao.session(deviceId)
        dao.saveSession(OlmSessionEntity(deviceId, peer, d?.curve25519 ?: old!!.curve25519, d?.ed25519 ?: old!!.ed25519, s.pickle(key)))
    }

    /** Shows the message at once; it sends now, or stays queued until the connection is back. */
    suspend fun send(peer: String, text: String): Boolean {
        val row = DmMessageEntity(UuidV7.next(), peer, mine = true, body = text.trim(), status = "queued")
        dao.saveMessage(row)
        return deliver(row)
    }

    suspend fun retry(id: String) = dao.message(id)?.let { deliver(it.copy(status = "queued")) }
    suspend fun discard(id: String) = dao.deleteMessage(id)
    suspend fun flushQueued() { dao.queued().forEach { deliver(it) } }

    private suspend fun deliver(m: DmMessageEntity): Boolean = try {
        lock.withLock {
            val acc = ready()
            val myDevice = session.deviceId()
            // Every device of theirs, and our own other devices so the conversation follows the account.
            val theirs = api.userDevices(m.peerUserId)
            if (theirs.isEmpty()) throw NoDevices()
            val mine = api.userDevices(me()).filter { it.deviceId != myDevice }
            val targets = theirs + mine
            recordKeys(m.peerUserId, theirs)
            val missing = targets.filter { storedSession(it.deviceId) == null }
            val claimed = if (missing.isEmpty()) emptyMap() else api.claimKeys(missing.map { it.deviceId }).associateBy { it.deviceId }
            val payload = api.json.encodeToString(DmPayload.serializer(), DmPayload(text = m.body, sentAt = m.createdAt))
            val envelopes = targets.mapNotNull { d ->
                val s = storedSession(d.deviceId) ?: claimed[d.deviceId]?.let { acc.createOutboundSession(d.curve25519, it.key) } ?: return@mapNotNull null
                val e = s.encrypt(payload)
                keep(d.deviceId, if (d in mine) me() else m.peerUserId, d, s)
                EnvelopeOutDto(d.deviceId, e.msgType.toInt(), e.body)
            }
            api.sendDm(m.peerUserId, myDevice, m.id, envelopes)
            dao.saveMessage(m.copy(status = "sent"))
        }
        true
    } catch (e: OfflineException) {
        dao.saveMessage(m.copy(status = "queued")); false
    } catch (e: Exception) {
        dao.saveMessage(m.copy(status = if (e is NoDevices) "no_devices" else "failed")); false
    }

    /** Fetches, decrypts and stores what's waiting for this device, then tells the server to forget it. */
    suspend fun receive() = lock.withLock {
        val acc = ready()
        val device = session.deviceId()
        val me = me()
        val envelopes = api.inbox(device)
        if (envelopes.isEmpty()) return@withLock
        val done = mutableListOf<String>()
        for (e in envelopes) {
            val text = decrypt(acc, e)
            val parsed = text?.let(::parse)
            val mine = e.from == me                          // a copy of something we sent from another device
            if (dao.message(e.clientId) == null) {
                dao.saveMessage(DmMessageEntity(e.clientId, e.peer, mine, parsed?.text ?: text.orEmpty(), parsed?.sentAt ?: e.createdAt,
                    status = if (text == null) "undecryptable" else "sent", read = mine))
            }
            done += e.id
        }
        dao.saveAccount(OlmAccountEntity(pickle = acc.pickle(key), registered = true))       // one-time keys used up
        api.ackInbox(device, done)
        maintainKeys(acc)
    }

    private fun parse(raw: String) = runCatching { api.json.decodeFromString(DmPayload.serializer(), raw) }.getOrNull()

    private suspend fun decrypt(acc: OlmAccount, e: EnvelopeDto): String? {
        val peer = if (e.from == me()) me() else e.from
        storedSession(e.fromDevice)?.let { s ->
            runCatching { s.decrypt(e.type.toUByte(), e.body) }.getOrNull()?.let { keep(e.fromDevice, peer, null, s); return it }
        }
        // A first message (or the other side started over): a new inbound session from the pre-key message.
        if (e.type != 0 || e.senderKey == null) return null
        return runCatching {
            val inbound = acc.createInboundSession(e.senderKey, e.body)
            val keys = api.userDevices(e.from).find { it.deviceId == e.fromDevice }
            dao.saveSession(OlmSessionEntity(e.fromDevice, peer, e.senderKey, keys?.ed25519 ?: "", inbound.session.pickle(key)))
            inbound.plaintext
        }.getOrNull()
    }

    /** Remembers the person's current device keys, so a change after verifying shows a warning in the thread. */
    private suspend fun recordKeys(peer: String, devices: List<DeviceKeysDto>) {
        val keys = devices.map { it.ed25519 }.sorted().joinToString(",")
        val p = dao.peer(peer) ?: DmPeerEntity(peer, "")
        if (p.currentKeys != keys) dao.savePeer(p.copy(currentKeys = keys))
    }

    /** One safety number per device of theirs (usually one). Compare in person or on a call. */
    suspend fun safetyNumbers(peer: String): List<List<String>> {
        val mineKey = lock.withLock { ready().identityKeys().ed25519 }
        val theirs = api.userDevices(peer)
        recordKeys(peer, theirs)
        return theirs.map { safetyNumber(mineKey, it.ed25519) }
    }

    suspend fun markVerified(peer: String) {
        val p = dao.peer(peer) ?: return
        dao.savePeer(p.copy(verifiedKeys = p.currentKeys))
    }

    /** Report: the reporter's device sends the selected messages it decrypted (the server can't read DMs). */
    suspend fun report(peer: String, messages: List<DmMessageEntity>) {
        api.reportUser(peer, "dm", messages.filter { !it.mine }.takeLast(20).map { it.body })
    }

    class NoDevices : Exception()

    private companion object { const val MIN_KEYS = 20; const val TARGET_KEYS = 50 }
}

/** Safety-number state for a thread. */
enum class Verification { Unverified, Verified, Changed }
fun DmPeerEntity?.verification(): Verification = when {
    this?.verifiedKeys == null -> Verification.Unverified
    verifiedKeys == currentKeys -> Verification.Verified
    else -> Verification.Changed
}
