package com.menuflow.ifood

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

/**
 * Cifra/decifra tokens iFood (client_secret, app_token, access/refresh token) com
 * AES-256-GCM. Cada operacao gera um IV aleatorio de 12 bytes (armazenado no campo
 * *_iv ao lado do *_enc). O tag de autenticacao de 128 bits garante integridade:
 * decrypt lanca AEADBadTagException se o ciphertext/IV/chave nao baterem.
 *
 * A chave (32 bytes / 256 bits, Base64) vem de ifood.encryption.key -> env
 * IFOOD_ENCRYPTION_KEY em producao. NUNCA logar plaintext nem a chave.
 */
@Component
class IfoodTokenCipher(
    @Value("\${ifood.encryption.key}") private val keyBase64: String
) {
    companion object {
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
    }

    private val key: SecretKey by lazy {
        val decoded = Base64.getDecoder().decode(keyBase64)
        require(decoded.size == 32) { "ifood.encryption.key deve ser 32 bytes (256 bits) em Base64" }
        SecretKeySpec(decoded, "AES")
    }

    fun encrypt(plaintext: String): Pair<ByteArray, ByteArray> {
        val iv = ByteArray(GCM_IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)) to iv
    }

    fun decrypt(ciphertext: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }
}
