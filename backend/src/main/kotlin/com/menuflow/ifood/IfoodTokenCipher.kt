package com.menuflow.ifood

import com.menuflow.crypto.SecretCipher
import org.springframework.stereotype.Component

/**
 * Cifra/decifra tokens iFood (client_secret, app_token, access/refresh token) com
 * AES-256-GCM.
 *
 * A implementacao criptografica foi EXTRAIDA para [SecretCipher] (bean neutro,
 * reutilizavel por outros modulos — ex.: chaves de API da plataforma). Este bean
 * agora apenas DELEGA, preservando 100% do contrato publico usado hoje por iFood,
 * GA4 (api_secret) e TOTP: `encrypt(plaintext): Pair<enc, iv>` e
 * `decrypt(ciphertext, iv): String`, mesma chave (IFOOD_ENCRYPTION_KEY).
 *
 * Mantido como tipo proprio (nao um alias) para nao tocar nas ~10 injecoes
 * existentes de IfoodTokenCipher e deixar clara a intencao no dominio iFood.
 */
@Component
class IfoodTokenCipher(
    private val cipher: SecretCipher,
) {
    fun encrypt(plaintext: String): Pair<ByteArray, ByteArray> = cipher.encrypt(plaintext)

    fun decrypt(ciphertext: ByteArray, iv: ByteArray): String = cipher.decrypt(ciphertext, iv)
}
