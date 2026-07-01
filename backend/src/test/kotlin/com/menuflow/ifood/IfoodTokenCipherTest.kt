package com.menuflow.ifood

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException

/**
 * Testes unitarios do IfoodTokenCipher (AES-256-GCM). Nao sobem contexto Spring:
 * instanciam o cipher com uma chave de teste valida (32 bytes em Base64).
 */
class IfoodTokenCipherTest {

    // 32 bytes ("testkeytestkeytestkeytestkey" tem 28 -> nao serve); usamos 32 'A' bytes.
    private val testKeyBase64 = Base64.getEncoder().encodeToString(ByteArray(32) { 'A'.code.toByte() })
    private val cipher = IfoodTokenCipher(testKeyBase64)

    @Test
    fun `round-trip encrypt then decrypt returns original`() {
        val plaintext = "ifood-access-token-abc123!@#-áéí"
        val (ct, iv) = cipher.encrypt(plaintext)
        assertEquals(plaintext, cipher.decrypt(ct, iv))
    }

    @Test
    fun `IV differs on each call (non-deterministic)`() {
        val plaintext = "same-secret"
        val (ct1, iv1) = cipher.encrypt(plaintext)
        val (ct2, iv2) = cipher.encrypt(plaintext)
        assertFalse(iv1.contentEquals(iv2), "IV deve ser aleatorio a cada encrypt")
        assertFalse(ct1.contentEquals(ct2), "ciphertext deve diferir por IV distinto")
        // ambos decifram para o mesmo plaintext
        assertEquals(plaintext, cipher.decrypt(ct1, iv1))
        assertEquals(plaintext, cipher.decrypt(ct2, iv2))
    }

    @Test
    fun `decrypt with wrong IV throws`() {
        val (ct, _) = cipher.encrypt("secret")
        val wrongIv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        assertThrows(AEADBadTagException::class.java) {
            cipher.decrypt(ct, wrongIv)
        }
    }

    @Test
    fun `decrypt with tampered ciphertext throws`() {
        val (ct, iv) = cipher.encrypt("secret")
        ct[0] = (ct[0].toInt() xor 0xFF).toByte()
        assertThrows(AEADBadTagException::class.java) {
            cipher.decrypt(ct, iv)
        }
    }

    @Test
    fun `key with wrong size is rejected`() {
        val shortKey = Base64.getEncoder().encodeToString(ByteArray(16))
        val bad = IfoodTokenCipher(shortKey)
        assertThrows(IllegalArgumentException::class.java) {
            bad.encrypt("x") // forca o lazy da chave
        }
    }
}
