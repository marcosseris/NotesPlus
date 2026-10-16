package com.notesplus.app.cloud.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Tiny secrets store: values are AES-GCM encrypted with a key that lives in the
 * Android Keystore (never leaves the secure hardware on devices that have it).
 */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure_store", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    @Synchronized
    fun put(name: String, value: String?) {
        if (value == null) { prefs.edit().remove(name).apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val blob = ByteArray(1 + iv.size + ct.size)
        blob[0] = iv.size.toByte()
        System.arraycopy(iv, 0, blob, 1, iv.size)
        System.arraycopy(ct, 0, blob, 1 + iv.size, ct.size)
        prefs.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    @Synchronized
    fun get(name: String): String? {
        val enc = prefs.getString(name, null) ?: return null
        return try {
            val blob = Base64.decode(enc, Base64.NO_WRAP)
            val ivLen = blob[0].toInt()
            val iv = blob.copyOfRange(1, 1 + ivLen)
            val ct = blob.copyOfRange(1 + ivLen, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            // Key was rotated / wiped (e.g. after a device restore): treat as absent.
            prefs.edit().remove(name).apply()
            null
        }
    }

    fun clear() = prefs.edit().clear().apply()

    private companion object { const val ALIAS = "notesplus_master_key" }
}
