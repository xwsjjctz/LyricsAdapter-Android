package com.lyricsadapter.pluginhost

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The primitives behind the compat layer's node:crypto, named the way Node names them. */
interface HostCrypto {
    fun randomBytes(size: Int): ByteArray
    fun randomUUID(): String
    fun cipher(algorithm: String, encrypt: Boolean, key: ByteArray, iv: ByteArray?, data: ByteArray, autoPadding: Boolean): ByteArray
    fun digest(algorithm: String, data: ByteArray): ByteArray
    fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray
}

/** Java Cryptography Architecture implementation, available on the JVM and on Android. */
class JcaHostCrypto : HostCrypto {
    private val random = SecureRandom()

    override fun randomBytes(size: Int): ByteArray {
        require(size in 0..MAX_RANDOM_BYTES) { "Random byte count out of range: $size" }
        return ByteArray(size).also(random::nextBytes)
    }

    override fun randomUUID(): String = UUID.randomUUID().toString()

    override fun cipher(algorithm: String, encrypt: Boolean, key: ByteArray, iv: ByteArray?, data: ByteArray, autoPadding: Boolean): ByteArray {
        val spec = AesSpec.parse(algorithm)
        require(key.size == spec.keyBytes) { "Invalid key length" }
        val padding = if (autoPadding && spec.mode != "CTR") "PKCS5Padding" else "NoPadding"
        val cipher = Cipher.getInstance("AES/${spec.mode}/$padding")
        val mode = if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE
        val secretKey = SecretKeySpec(key, "AES")
        if (spec.mode == "ECB") {
            cipher.init(mode, secretKey)
        } else {
            require(iv != null && iv.size == AES_BLOCK_BYTES) { "Invalid initialization vector" }
            cipher.init(mode, secretKey, IvParameterSpec(iv))
        }
        return cipher.doFinal(data)
    }

    override fun digest(algorithm: String, data: ByteArray): ByteArray =
        MessageDigest.getInstance(hashName(algorithm)).digest(data)

    override fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
        val name = "Hmac" + hashName(algorithm).replace("-", "")
        val mac = Mac.getInstance(name)
        mac.init(SecretKeySpec(key, name))
        return mac.doFinal(data)
    }

    private fun hashName(algorithm: String): String =
        HASH_NAMES[algorithm.lowercase()] ?: throw IllegalArgumentException("Digest method not supported: $algorithm")

    private class AesSpec(val keyBytes: Int, val mode: String) {
        companion object {
            private val PATTERN = Regex("""aes-(128|192|256)-(cbc|ecb|ctr)""")

            fun parse(algorithm: String): AesSpec {
                val match = PATTERN.matchEntire(algorithm.lowercase()) ?: throw IllegalArgumentException("Unknown cipher: $algorithm")
                return AesSpec(keyBytes = match.groupValues[1].toInt() / 8, mode = match.groupValues[2].uppercase())
            }
        }
    }

    private companion object {
        const val MAX_RANDOM_BYTES = 65536
        const val AES_BLOCK_BYTES = 16
        val HASH_NAMES = mapOf(
            "md5" to "MD5",
            "sha1" to "SHA-1",
            "sha224" to "SHA-224",
            "sha256" to "SHA-256",
            "sha384" to "SHA-384",
            "sha512" to "SHA-512",
        )
    }
}
