package com.menuflow.crypto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException

/**
 * Testes unitarios do SecretCipher (AES-256-GCM neutro), sem contexto Spring.
 * Provam que a implementacao extraida preserva as garantias que o IfoodTokenCipher
 * tinha: round-trip, IV nao-deterministico, integridade (tag) e rejeicao de chave
 * de tamanho errado.
 */
class SecretCipherTest {

    private val testKeyBase64 = Base64.getEncoder().encodeToString(ByteArray(32) { 'A'.code.toByte() })
    private val cipher = SecretCipher(testKeyBase64)

    @Test
    fun `round-trip encrypt then decrypt returns original`() {
        val plaintext = "google-maps-api-key-AIzaSy...-áéí!@#"
        val (ct, iv) = cipher.encrypt(plaintext)
        // O ciphertext e BYTEA cifrado, NAO o texto claro.
        assertFalse(ct.contentEquals(plaintext.toByteArray(Charsets.UTF_8)), "valor gravado nao pode ser o texto claro")
        assertEquals(plaintext, cipher.decrypt(ct, iv))
    }

    @Test
    fun `IV differs on each call (non-deterministic)`() {
        val (ct1, iv1) = cipher.encrypt("same-secret")
        val (ct2, iv2) = cipher.encrypt("same-secret")
        assertFalse(iv1.contentEquals(iv2), "IV deve ser aleatorio a cada encrypt")
        assertFalse(ct1.contentEquals(ct2), "ciphertext deve diferir por IV distinto")
    }

    @Test
    fun `decrypt with tampered ciphertext throws`() {
        val (ct, iv) = cipher.encrypt("secret")
        ct[0] = (ct[0].toInt() xor 0xFF).toByte()
        assertThrows(AEADBadTagException::class.java) { cipher.decrypt(ct, iv) }
    }

    @Test
    fun `decrypt with wrong IV throws`() {
        val (ct, _) = cipher.encrypt("secret")
        val wrongIv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        assertThrows(AEADBadTagException::class.java) { cipher.decrypt(ct, wrongIv) }
    }

    @Test
    fun `key with wrong size is rejected`() {
        val shortKey = Base64.getEncoder().encodeToString(ByteArray(16))
        assertThrows(IllegalArgumentException::class.java) {
            SecretCipher(shortKey).encrypt("x") // forca o lazy da chave
        }
    }
}
