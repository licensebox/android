package dev.licensebox.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import dev.licensebox.android.api.DEFAULT_SERVER
import java.security.KeyStore
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A signed-in admin: the server, who they are, and their session token. */
data class SignedIn(val server: String, val email: String, val token: String, val expires: Instant?)

/**
 * Keeps the admin's session. The token is encrypted with an AES key held
 * by the Android Keystore, which never leaves the device; the app's data
 * is also left out of backups (see the manifest).
 */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    /** The server to sign in to: the last used, or licensebox.dev. */
    var server: String
        get() = prefs.getString("server", null) ?: DEFAULT_SERVER
        set(v) = prefs.edit { putString("server", v) }

    var lastEmail: String
        get() = prefs.getString("last_email", null).orEmpty()
        set(v) = prefs.edit { putString("last_email", v) }

    fun load(): SignedIn? {
        val sealed = prefs.getString("token", null) ?: return null
        val token = runCatching { open(sealed) }.getOrNull() ?: return null.also { clear() }
        val expires = prefs.getLong("expires", 0).takeIf { it > 0 }?.let(Instant::ofEpochSecond)
        if (expires != null && Instant.now().isAfter(expires)) return null.also { clear() }
        return SignedIn(server, prefs.getString("email", null).orEmpty(), token, expires)
    }

    fun save(s: SignedIn) = prefs.edit {
        putString("server", s.server)
        putString("email", s.email)
        putString("last_email", s.email)
        putString("token", seal(s.token))
        putLong("expires", s.expires?.epochSecond ?: 0)
    }

    fun clear() = prefs.edit {
        remove("token")
        remove("email")
        remove("expires")
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return g.generateKey()
    }

    private fun seal(plain: String): String {
        val c = Cipher.getInstance(AES_GCM)
        c.init(Cipher.ENCRYPT_MODE, key())
        val b64 = { b: ByteArray -> Base64.encodeToString(b, Base64.NO_WRAP) }
        return b64(c.iv) + ":" + b64(c.doFinal(plain.toByteArray()))
    }

    private fun open(sealed: String): String {
        val (iv, data) = sealed.split(":", limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
        val c = Cipher.getInstance(AES_GCM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(c.doFinal(data))
    }

    private companion object {
        const val ALIAS = "licensebox-session"
        const val AES_GCM = "AES/GCM/NoPadding"
    }
}
