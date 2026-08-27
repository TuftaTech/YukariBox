package dev.yukaribox.vpn.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Optional password encryption for exported backups/config. AES-256-GCM with a
 * PBKDF2 (HMAC-SHA256) key derived from the user's password; a fresh random salt
 * and IV per export are stored next to the ciphertext in a small JSON envelope.
 * GCM's authentication tag makes a wrong password or a tampered file fail to
 * decrypt rather than yield garbage. Pure JCA — no Android dependency, so the
 * encrypt/decrypt round-trip is fully unit-testable.
 */
object BackupCrypto {

    private const val MAGIC = "yukaribox_enc"
    private const val VERSION = 1

    /**
     * PBKDF2-HMAC-SHA256 work factor for new exports. Current OWASP guidance for this
     * PRF is 600 000 (210 000 is the figure for HMAC-SHA512, which is what this used to
     * carry). Backups stay portable because [decrypt] uses the count recorded in the
     * envelope, so files written with the old factor still open.
     */
    private const val ITERATIONS = 600_000

    /**
     * Accepted bounds for the *file-supplied* iteration count. It is attacker-controlled
     * input: a crafted envelope claiming two billion iterations would hang the import in
     * key derivation, and one claiming a handful would make a brute-force of the chosen
     * password trivial if the user re-used it elsewhere.
     */
    private const val MIN_ACCEPTED_ITERATIONS = 100_000
    private const val MAX_ACCEPTED_ITERATIONS = 1_000_000

    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    @Serializable
    private data class Envelope(
        @SerialName(MAGIC) val version: Int,
        val kdf: String,
        val iter: Int,
        val salt: String,
        val iv: String,
        val data: String,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val encoder: Base64.Encoder = Base64.getEncoder()
    private val decoder: Base64.Decoder = Base64.getDecoder()

    /** True if [text] looks like a BackupCrypto envelope (i.e. an encrypted export). */
    fun isEncrypted(text: String): Boolean = text.contains("\"$MAGIC\"")

    /** Encrypt [plaintext] with [password]; returns a self-describing JSON envelope. */
    fun encrypt(plaintext: String, password: String): String {
        val rnd = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(rnd::nextBytes)
        val iv = ByteArray(IV_BYTES).also(rnd::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, ITERATIONS), GCMParameterSpec(TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val env = Envelope(
            version = VERSION,
            kdf = "pbkdf2-sha256",
            iter = ITERATIONS,
            salt = encoder.encodeToString(salt),
            iv = encoder.encodeToString(iv),
            data = encoder.encodeToString(ciphertext),
        )
        return json.encodeToString(Envelope.serializer(), env)
    }

    /** Decrypt an [envelope] from [encrypt]; throws on a wrong password or tampering. */
    fun decrypt(envelope: String, password: String): String {
        val env = json.decodeFromString(Envelope.serializer(), envelope)
        require(env.version in 1..VERSION) { "unsupported backup encryption version ${env.version}" }
        require(env.iter in MIN_ACCEPTED_ITERATIONS..MAX_ACCEPTED_ITERATIONS) {
            "backup KDF iteration count ${env.iter} is outside the accepted range"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            deriveKey(password, decoder.decode(env.salt), env.iter),
            GCMParameterSpec(TAG_BITS, decoder.decode(env.iv)),
        )
        return String(cipher.doFinal(decoder.decode(env.data)), Charsets.UTF_8)
    }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val bytes = factory.generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)).encoded
        return SecretKeySpec(bytes, "AES")
    }
}
