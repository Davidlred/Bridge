package app.thebridge.util

import java.security.SecureRandom
import java.util.UUID

/** UUIDv7 (48-bit unix-ms timestamp + random). Every row created on device gets one, so offline creates never need the server. */
object UuidV7 {
    private val random = SecureRandom()

    fun next(nowMs: Long = System.currentTimeMillis()): String {
        val r = ByteArray(10).also(random::nextBytes)
        val randA = ((r[0].toLong() and 0x0F) shl 8) or (r[1].toLong() and 0xFF)
        val msb = (nowMs shl 16) or (0x7L shl 12) or randA
        var lsb = 0L
        for (i in 2 until 10) lsb = (lsb shl 8) or (r[i].toLong() and 0xFF)
        lsb = (lsb and 0x3FFFFFFFFFFFFFFFL) or Long.MIN_VALUE     // variant 10
        return UUID(msb, lsb).toString()
    }
}
