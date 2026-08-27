package dev.yukaribox.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round-trip, authentication and detection guarantees of the backup password layer. */
class BackupCryptoTest {

    private val sample =
        "vless://11111111-1111-1111-1111-111111111111@example.com:443#node-a\n" +
            "ss://YWVzLTI1Ni1nY206cGFzcw@example.org:8388#node-b"

    @Test
    fun roundTripRecoversPlaintext() {
        val env = BackupCrypto.encrypt(sample, "Panzer vor!")
        assertTrue(BackupCrypto.isEncrypted(env))
        assertEquals(sample, BackupCrypto.decrypt(env, "Panzer vor!"))
    }

    @Test
    fun wrongPasswordFails() {
        val env = BackupCrypto.encrypt(sample, "correct horse")
        assertThrows(Exception::class.java) { BackupCrypto.decrypt(env, "battery staple") }
    }

    @Test
    fun tamperedCiphertextFails() {
        val env = BackupCrypto.encrypt(sample, "pw")
        // Flip a character inside the base64 `data` field (last value of the envelope).
        val idx = env.length - 4
        val flipped = if (env[idx] == 'A') 'B' else 'A'
        val tampered = env.substring(0, idx) + flipped + env.substring(idx + 1)
        assertThrows(Exception::class.java) { BackupCrypto.decrypt(tampered, "pw") }
    }

    @Test
    fun plaintextLinkListIsNotDetectedAsEncrypted() {
        assertFalse(BackupCrypto.isEncrypted(sample))
    }

    @Test
    fun freshSaltAndIvPerExport() {
        // Same plaintext + password must still yield different envelopes (random salt/IV).
        assertNotEquals(BackupCrypto.encrypt(sample, "pw"), BackupCrypto.encrypt(sample, "pw"))
    }

    // ---- the file-supplied KDF work factor is attacker-controlled input ----

    /** Rewrite the `iter` field of a real envelope, leaving everything else intact. */
    private fun withIterations(envelope: String, iterations: Int): String =
        envelope.replace(Regex("\"iter\":\\s*\\d+"), "\"iter\":$iterations")

    @Test
    fun aTooWeakIterationCountIsRejected() {
        // A crafted envelope claiming a handful of rounds would make brute-forcing the
        // password trivial — and the password is one the user may have re-used.
        val env = withIterations(BackupCrypto.encrypt(sample, "pw"), 10)
        val error = assertThrows(IllegalArgumentException::class.java) { BackupCrypto.decrypt(env, "pw") }
        assertTrue(error.message!!.contains("accepted range"))
    }

    /** Timeout is the point: two billion rounds must be refused, not attempted. */
    @Test(timeout = 20_000)
    fun anAbsurdIterationCountIsRefusedInsteadOfAttempted() {
        val env = withIterations(BackupCrypto.encrypt(sample, "pw"), 2_000_000_000)
        val error = assertThrows(IllegalArgumentException::class.java) { BackupCrypto.decrypt(env, "pw") }
        assertTrue(error.message!!.contains("accepted range"))
    }

    @Test
    fun aLegacyWorkFactorInsideTheBoundsStillPassesTheCheck() {
        // Backups written with the older 210 000-round factor must stay openable, so the
        // bound check must not be the thing that rejects them. (Here the payload was
        // encrypted with the current factor, so decryption still fails the GCM tag —
        // what matters is that it is *not* the range check that fires.)
        val env = withIterations(BackupCrypto.encrypt(sample, "pw"), 210_000)
        val error = assertThrows(Exception::class.java) { BackupCrypto.decrypt(env, "pw") }
        assertFalse(error.message.orEmpty().contains("accepted range"))
    }
}
