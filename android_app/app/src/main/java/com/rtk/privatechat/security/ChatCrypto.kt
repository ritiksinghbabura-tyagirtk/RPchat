package com.rtk.privatechat.security

import android.util.Base64
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Initial E2EE transport: static X25519 DH + HKDF-SHA256 + AES-256-GCM,
 * with Ed25519 signatures over each encrypted envelope.
 * Not a Signal-style ratchet; no forward secrecy or automatic key rotation.
 */
object ChatCrypto {
    data class Encrypted(val ciphertext: String, val nonce: String)
    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(value: String) = Base64.decode(value, Base64.DEFAULT)

    private fun deriveKey(material: IdentityMaterial, peerPublicBase64: String,
                          conversationId: String): ByteArray {
        val peerBytes = unb64(peerPublicBase64)
        require(peerBytes.size == 32) { "Invalid contact exchange key" }
        val shared = ByteArray(32)
        X25519PrivateKeyParameters(material.exchangeSeed, 0)
            .generateSecret(X25519PublicKeyParameters(peerBytes, 0), shared, 0)
        require(shared.any { it != 0.toByte() }) { "Invalid contact exchange key" }
        try {
            val extract = Mac.getInstance("HmacSHA256")
            extract.init(SecretKeySpec(conversationId.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val prk = extract.doFinal(shared)
            val expand = Mac.getInstance("HmacSHA256")
            expand.init(SecretKeySpec(prk, "HmacSHA256"))
            expand.update("RTK_PRIVATE_CHAT_E2EE_V1".toByteArray(Charsets.UTF_8))
            expand.update(0x01.toByte())
            return expand.doFinal().copyOf(32)
        } finally { shared.fill(0) }
    }

    private fun signedBytes(conversationId: String, nonce: String, encrypted: String) =
        "$conversationId|$nonce|$encrypted".toByteArray(Charsets.UTF_8)

    fun encrypt(material: IdentityMaterial, peerExchangePublic: String,
                conversationId: String, plaintext: String): Encrypted {
        val key = deriveKey(material, peerExchangePublic, conversationId)
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(conversationId.toByteArray(Charsets.UTF_8))
            val encrypted = b64(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
            val nonceText = b64(nonce)
            val signer = Ed25519Signer()
            signer.init(true, Ed25519PrivateKeyParameters(material.signingSeed, 0))
            val signed = signedBytes(conversationId, nonceText, encrypted)
            signer.update(signed, 0, signed.size)
            val signature = b64(signer.generateSignature())
            val envelope = JSONObject().put("v", 1).put("ct", encrypted).put("sig", signature)
            return Encrypted(b64(envelope.toString().toByteArray(Charsets.UTF_8)), nonceText)
        } finally { key.fill(0) }
    }

    fun decrypt(material: IdentityMaterial, peerExchangePublic: String,
                peerSigningPublic: String, conversationId: String,
                ciphertext: String, nonceText: String): String {
        val envelope = JSONObject(String(unb64(ciphertext), Charsets.UTF_8))
        require(envelope.optInt("v") == 1) { "Unsupported encrypted message version" }
        val encrypted = envelope.getString("ct")
        val signature = unb64(envelope.getString("sig"))
        val signingKey = unb64(peerSigningPublic)
        require(signingKey.size == 32 && signature.size == 64) { "Invalid message signature" }
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(signingKey, 0))
        val signed = signedBytes(conversationId, nonceText, encrypted)
        verifier.update(signed, 0, signed.size)
        require(verifier.verifySignature(signature)) { "Message signature verification failed" }

        val key = deriveKey(material, peerExchangePublic, conversationId)
        val nonce = unb64(nonceText)
        require(nonce.size == 12) { "Invalid message nonce" }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(conversationId.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(unb64(encrypted)), Charsets.UTF_8)
        } finally { key.fill(0) }
    }
}
