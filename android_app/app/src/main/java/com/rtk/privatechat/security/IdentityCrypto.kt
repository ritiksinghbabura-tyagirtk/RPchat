package com.rtk.privatechat.security

import android.content.Context
import android.util.Base64
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.text.Normalizer

data class IdentityMaterial(
    val signingSeed: ByteArray,
    val exchangeSeed: ByteArray,
    val signingPublicKey: ByteArray,
    val exchangePublicKey: ByteArray
) {
    fun signingPublicBase64(): String =
        Base64.encodeToString(signingPublicKey, Base64.NO_WRAP)

    fun exchangePublicBase64(): String =
        Base64.encodeToString(exchangePublicKey, Base64.NO_WRAP)
}

object IdentityCrypto {
    private val random = SecureRandom()

    private fun wordList(context: Context): List<String> =
        context.assets.open("bip39_english.txt")
            .bufferedReader()
            .use { it.readLines().map(String::trim).filter(String::isNotEmpty) }

    fun generateMnemonic(context: Context): String {
        val words = wordList(context)
        require(words.size == 2048) { "BIP39 wordlist must contain 2048 words" }

        val entropy = ByteArray(16)
        random.nextBytes(entropy)

        val checksum = MessageDigest.getInstance("SHA-256")
            .digest(entropy)

        val bits = buildString {
            entropy.forEach { byte ->
                append((byte.toInt() and 0xff).toString(2).padStart(8, '0'))
            }
            append((checksum[0].toInt() and 0xff).toString(2).padStart(8, '0'), 0, 4)
        }

        return (0 until 12).joinToString(" ") { index ->
            val start = index * 11
            val value = bits.substring(start, start + 11).toInt(2)
            words[value]
        }
    }

    fun validateMnemonic(context: Context, phrase: String): Boolean {
        return try {
            val words = wordList(context)
            val parts = phrase.trim().lowercase().split(Regex("\\s+"))
            if (parts.size != 12 || words.size != 2048) return false

            val bits = buildString {
                for (word in parts) {
                    val index = words.indexOf(word)
                    if (index < 0) return false
                    append(index.toString(2).padStart(11, '0'))
                }
            }

            val entropyBits = bits.substring(0, 128)
            val checksumBits = bits.substring(128, 132)

            val entropy = ByteArray(16) { i ->
                entropyBits.substring(i * 8, i * 8 + 8).toInt(2).toByte()
            }

            val actualChecksum = MessageDigest.getInstance("SHA-256")
                .digest(entropy)[0].toInt() and 0xff

            checksumBits == actualChecksum.toString(2)
                .padStart(8, '0').substring(0, 4)
        } catch (_: Exception) {
            false
        }
    }

    fun fromMnemonic(context: Context, phrase: String): IdentityMaterial {
        require(validateMnemonic(context, phrase)) {
            "Invalid 12-word recovery phrase or incorrect word order"
        }

        val normalized = Normalizer.normalize(
            phrase.trim().lowercase(),
            Normalizer.Form.NFKD
        )

        val salt = Normalizer.normalize("mnemonic", Normalizer.Form.NFKD)
            .toByteArray(Charsets.UTF_8)

        val spec = PBEKeySpec(
            normalized.toCharArray(),
            salt,
            2048,
            512
        )

        val seed = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
                .generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }

        val signingSeed = derive(seed, "RTK_PRIVATE_ED25519_V1")
        val exchangeSeed = derive(seed, "RTK_PRIVATE_X25519_V1")

        val signingPrivate = Ed25519PrivateKeyParameters(signingSeed, 0)
        val exchangePrivate = X25519PrivateKeyParameters(exchangeSeed, 0)

        return IdentityMaterial(
            signingSeed = signingSeed,
            exchangeSeed = exchangeSeed,
            signingPublicKey = signingPrivate.generatePublicKey().encoded,
            exchangePublicKey = exchangePrivate.generatePublicKey().encoded
        )
    }

    fun fromStoredSeeds(
        signingSeed: ByteArray,
        exchangeSeed: ByteArray
    ): IdentityMaterial {
        require(signingSeed.size == 32 && exchangeSeed.size == 32)

        val signingPrivate = Ed25519PrivateKeyParameters(signingSeed, 0)
        val exchangePrivate = X25519PrivateKeyParameters(exchangeSeed, 0)

        return IdentityMaterial(
            signingSeed,
            exchangeSeed,
            signingPrivate.generatePublicKey().encoded,
            exchangePrivate.generatePublicKey().encoded
        )
    }

    private fun derive(seed: ByteArray, label: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(seed, "HmacSHA256"))
        return mac.doFinal(label.toByteArray(Charsets.UTF_8)).copyOf(32)
    }
}
