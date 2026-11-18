package com.rtk.privatechat.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SessionStore {
    private const val PREFS = "rtk_private_secure_session"
    private const val KEY_ALIAS = "rtk_private_session_aes_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun getKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
        }

        val existing = keyStore.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )

        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )

        return generator.generateKey()
    }

    fun saveToken(context: Context, token: String) {
        require(token.isNotBlank())

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getKey())

        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val combined = cipher.iv + encrypted
        val encoded = Base64.encodeToString(combined, Base64.NO_WRAP)

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("session_blob", encoded)
            .commit()
    }

    fun getToken(context: Context): String? {
        return try {
            val encoded = context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE
            ).getString("session_blob", null) ?: return null

            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size <= 12) return null

            val iv = combined.copyOfRange(0, 12)
            val encrypted = combined.copyOfRange(12, combined.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getKey(),
                GCMParameterSpec(128, iv)
            )

            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            clear(context)
            null
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}
