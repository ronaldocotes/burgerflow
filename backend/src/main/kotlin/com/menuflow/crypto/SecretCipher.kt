package com.menuflow.crypto

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cifra/decifra segredos GENERICOS (qualquer texto sensivel guardado no banco) com
 * AES-256-GCM. Cada operacao gera um IV aleatorio de 12 bytes (armazenado no campo
 * *_iv ao lado do *_enc). O tag de autenticacao de 128 bits garante integridade:
 * decrypt lanca AEADBadTagException se ciphertext/IV/chave nao baterem.
 *
 * Esta e a implementacao neutra extraida do IfoodTokenCipher (Fase 1 do Gerenciador
 * de Chaves de API da Plataforma): a mesma AES-256-GCM que ja cifra tokens iFood,
 * GA4 (api_secret) e TOTP passa a ser reutilizavel por outros modulos — o
 * IfoodTokenCipher agora DELEGA a este bean, sem mudar seu contrato publico.
 *
 * A chave (32 bytes / 256 bits, Base64) vem de ifood.encryption.key -> env
 * IFOOD_ENCRYPTION_KEY em producao (mantido o MESMO nome de env, para nao exigir
 * rotacao/redeploy). NUNCA logar plaintext nem a chave.
 */
@Component
class SecretCipher(
    @Value("\${ifood.encryption.key}") private val keyBase64: String,
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

    /** Cifra [plaintext] -> (ciphertext, iv). O IV e novo a cada chamada. */
    fun encrypt(plaintext: String): Pair<ByteArray, ByteArray> {
        val iv = ByteArray(GCM_IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)) to iv
    }

    /** Decifra. Lanca AEADBadTagException se ciphertext/IV/chave nao baterem. */
    fun decrypt(ciphertext: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }
}
