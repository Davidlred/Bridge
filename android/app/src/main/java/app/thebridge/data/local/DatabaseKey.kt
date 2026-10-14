package app.thebridge.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Passphrase for the SQLCipher database: 32 random bytes, stored wrapped by an AES key that lives in the Android Keystore.
 * The wrapped value and the database are both excluded from backup, so a restored phone starts clean and re-syncs.
 */
object DatabaseKey {
    const val FILE = "bridge-secure.db"
    private const val ALIAS = "bridge_db"
    private const val PREF = "db_key"

    /** Returns the passphrase, creating it on first launch. If it was lost (key store reset), the old database is deleted. */
    fun passphrase(ctx: Context): ByteArray {
        val prefs = ctx.getSharedPreferences("bridge_secure", Context.MODE_PRIVATE)
        prefs.getString(PREF, null)?.let { stored -> runCatching { return unwrap(stored) } }
        // No usable key: anything encrypted with an old one can't be read, so start fresh (the server copy re-syncs).
        ctx.deleteDatabase(FILE)
        markEmpty(ctx)
        val fresh = ByteArray(32).also(SecureRandom()::nextBytes)
        prefs.edit().putString(PREF, wrap(fresh)).commit()
        return fresh
    }

    /** The local database starts empty: the next sync must download everything again (cursor 0). */
    fun markEmpty(ctx: Context) { ctx.getSharedPreferences("bridge_secure", Context.MODE_PRIVATE).edit().putBoolean("resync", true).commit() }
    /** True once after [markEmpty]. */
    fun takeResync(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences("bridge_secure", Context.MODE_PRIVATE)
        return p.getBoolean("resync", false).also { if (it) p.edit().remove("resync").commit() }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            generateKey()
        }
    }

    private fun wrap(plain: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv + c.doFinal(plain), Base64.NO_WRAP)
    }

    private fun unwrap(stored: String): ByteArray {
        val blob = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12)) }
        return c.doFinal(blob, 12, blob.size - 12)
    }
}
