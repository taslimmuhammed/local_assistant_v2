package com.local.assistant.data.prefs

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets the user pastes in, like the Tavily API key: encrypted with a key that lives in the
 * Android Keystore and never leaves it, so the stored value is useless off this phone.
 */
class SecretStore(context: Context) {

    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    /** Decrypted values, so a check before every turn doesn't go to the Keystore. */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun get(name: String): String? = cache[name] ?: read(name)?.also { cache[name] = it }

    fun put(name: String, value: String?) {
        val clean = value?.trim()?.takeIf { it.isNotEmpty() }
        prefs.edit { if (clean == null) remove(name) else putString(name, encrypt(clean)) }
        if (clean == null) cache.remove(name) else cache[name] = clean
    }

    private fun read(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            decrypt(stored)
        } catch (e: Exception) {
            // The Keystore key is gone (e.g. restored onto another phone): ask for the secret again.
            Log.w(TAG, "Could not decrypt $name", e)
            null
        }
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val sealed = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        }
        return String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    companion object {
        const val TAVILY_KEY = "tavily.key"

        private const val TAG = "SecretStore"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "local_assistant_secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}
