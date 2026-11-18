package com.rtk.privatechat.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object IdentityVault {
    private const val PREFS = "rtk_private_identity_vault"
    private const val KEY_ALIAS = "rtk_private_identity_aes_v1"

    private fun getKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null)
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

    fun save(context: Context, material: IdentityMaterial) {
        val json = JSONObject().apply {
            put("signing_seed", Base64.encodeToString(material.signingSeed, Base64.NO_WRAP))
            put("exchange_seed", Base64.encodeToString(material.exchangeSeed, Base64.NO_WRAP))
            put("signing_public", material.signingPublicBase64())
            put("exchange_public", material.exchangePublicBase64())
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val combined = cipher.iv + encrypted

        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("identity_blob", Base64.encodeToString(combined, Base64.NO_WRAP))
            .commit()

        check(saved) { "Could not save identity securely" }
    }

    fun load(context: Context): IdentityMaterial? {
        return try {
            val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("identity_blob", null) ?: return null

            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            require(combined.size > 28)

            val iv = combined.copyOfRange(0, 12)
            val encrypted = combined.copyOfRange(12, combined.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getKey(),
                GCMParameterSpec(128, iv)
            )

            val json = JSONObject(
                String(cipher.doFinal(encrypted), Charsets.UTF_8)
            )

            val signingSeed = Base64.decode(
                json.getString("signing_seed"), Base64.NO_WRAP
            )
            val exchangeSeed = Base64.decode(
                json.getString("exchange_seed"), Base64.NO_WRAP
            )

            val material = IdentityCrypto.fromStoredSeeds(signingSeed, exchangeSeed)

            require(material.signingPublicBase64() == json.getString("signing_public"))
            require(material.exchangePublicBase64() == json.getString("exchange_public"))

            material
        } catch (_: Exception) {
            null
        }
    }

    fun exists(context: Context): Boolean = load(context) != null

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }
}
