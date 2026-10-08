package app.twodo.model

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Keys derived from a list's shared secret. */
class ListKeys(secret: String) {
    private val key = SecretKeySpec(sha256("twodo/enc/$secret"), "AES")

    /** 20-character room id announced to trackers. Reveals nothing about the secret. */
    val topic: String = sha256("twodo/topic/$secret").joinToString("") { "%02x".format(it) }.take(20)

    fun encrypt(plaintext: String): String {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plaintext.toByteArray()))
    }

    /** Returns null if [message] wasn't encrypted with this list's key. */
    fun decrypt(message: String): String? = runCatching {
        val bytes = Base64.getDecoder().decode(message)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, IV_BYTES))
        String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
    }.getOrNull()

    /** Compresses then encrypts; for relay messages, where size matters. */
    fun encryptCompressed(plaintext: String): String {
        val zipped = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write(plaintext.toByteArray()) }
        }.toByteArray()
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(zipped))
    }

    /** Reverses [encryptCompressed]; null if it wasn't made with this list's key. */
    fun decryptCompressed(message: String): String? = runCatching {
        val bytes = Base64.getDecoder().decode(message)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, IV_BYTES))
        val zipped = cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
        java.util.zip.GZIPInputStream(zipped.inputStream()).use { String(it.readBytes()) }
    }.getOrNull()

    companion object {
        private const val IV_BYTES = 12
        private val random = SecureRandom()

        fun newSecret(): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { random.nextBytes(it) })

        fun isValidSecret(secret: String): Boolean =
            runCatching { Base64.getUrlDecoder().decode(secret).size == 32 }.getOrDefault(false)

        private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
    }
}
