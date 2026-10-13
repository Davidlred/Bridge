package app.thebridge.data.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

data class Tokens(val access: String, val refresh: String)

interface TokenVault {
    fun read(): Tokens?
    fun write(tokens: Tokens?)
}

/** Tokens encrypted with an AES-GCM key that lives in the Android Keystore (never leaves the device, excluded from backup). */
@Singleton
class KeystoreTokenVault @Inject constructor(@ApplicationContext ctx: Context) : TokenVault {
    private val prefs = ctx.getSharedPreferences("bridge_secure", Context.MODE_PRIVATE)
    @Volatile private var cached: Tokens? = null
    @Volatile private var loaded = false

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            generateKey()
        }
    }

    @Synchronized
    override fun read(): Tokens? {
        if (loaded) return cached
        cached = runCatching {
            val blob = Base64.decode(prefs.getString(KEY, null) ?: return@runCatching null, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(AES_GCM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV)) }
            val (access, refresh) = String(cipher.doFinal(blob, IV, blob.size - IV)).split('\n', limit = 2)
            Tokens(access, refresh)
        }.getOrNull()                       // key lost (restore, factory reset): treated as signed out
        loaded = true
        return cached
    }

    @Synchronized
    override fun write(tokens: Tokens?) {
        cached = tokens; loaded = true
        if (tokens == null) { prefs.edit().remove(KEY).apply(); return }
        val cipher = Cipher.getInstance(AES_GCM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val blob = cipher.iv + cipher.doFinal("${tokens.access}\n${tokens.refresh}".toByteArray())
        prefs.edit().putString(KEY, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    private companion object { const val ALIAS = "bridge_tokens"; const val KEY = "tokens"; const val AES_GCM = "AES/GCM/NoPadding"; const val IV = 12 }
}
