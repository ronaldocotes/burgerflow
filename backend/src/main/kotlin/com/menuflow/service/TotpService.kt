package com.menuflow.service

import com.menuflow.dto.TotpSetupResponse
import com.menuflow.ifood.IfoodTokenCipher
import com.menuflow.repository.control.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

/**
 * Servico de 2FA via TOTP (RFC 6238) para SUPER_ADMINs.
 *
 * PERSISTENCIA (V15): o segredo ATIVO e cifrado em AES-256-GCM e guardado no banco
 * de CONTROLE (users.totp_secret_enc / totp_secret_iv), reusando o IfoodTokenCipher.
 * Assim o 2FA sobrevive a reinicializacoes. A chave de cifra vem de env
 * (ifood.encryption.key / IFOOD_ENCRYPTION_KEY), nunca do banco.
 *
 * EM MEMORIA (efemeros por natureza, nao precisam persistir):
 *  - pendingSecrets: segredo de um setup ainda NAO confirmado (o usuario refaz o
 *    setup se reiniciar antes de confirmar — comportamento aceitavel);
 *  - sessions: sessao intermediaria (sessionToken -> userId) de 5 min entre o
 *    login e o /auth/2fa/verify.
 * Em ambiente multi-instancia, migrar esses dois para Redis (o segredo ativo ja
 * esta no banco, entao verify/hasSecret ja funcionam cross-instancia).
 */
@Service
class TotpService(
    private val userRepository: UserRepository,
    private val cipher: IfoodTokenCipher,
) {

    private val random = SecureRandom()

    // segredo pendente de confirmacao por userId (setup iniciado, ainda nao confirmado) — efemero
    private val pendingSecrets = ConcurrentHashMap<UUID, ByteArray>()

    // token de sessao intermediaria -> (userId, expiry) — efemero
    private val sessions = ConcurrentHashMap<String, Pair<UUID, Instant>>()

    // ── API publica ──────────────────────────────────────────────────────────

    /** True se o usuario tem 2FA ativo (segredo cifrado presente no banco). */
    @Transactional("controlTransactionManager", readOnly = true)
    fun hasSecret(userId: UUID): Boolean {
        val user = userRepository.findById(userId).orElse(null) ?: return false
        return user.totpSecretEnc != null && user.totpSecretIv != null
    }

    /**
     * Inicia o setup: gera um segredo novo, guarda como PENDENTE (em memoria, nao
     * ativo ainda) e devolve o URI otpauth:// para o front gerar o QR code.
     * O usuario deve confirmar via confirmSetup() com o primeiro codigo do autenticador.
     */
    fun startSetup(userId: UUID): TotpSetupResponse {
        val secret = ByteArray(20).also { random.nextBytes(it) }
        pendingSecrets[userId] = secret
        val b32 = base32Encode(secret)
        val qrUri = "otpauth://totp/MenuFlow:admin-$userId?secret=$b32&issuer=MenuFlow&algorithm=SHA1&digits=6&period=30"
        return TotpSetupResponse(qrUri = qrUri, secret = b32)
    }

    /**
     * Confirma o setup verificando o codigo com o segredo pendente. Se valido, CIFRA
     * o segredo e o PERSISTE no banco (2FA passa a ativo), remove o pendente e retorna
     * true. Se invalido, o pendente permanece (usuario tenta de novo).
     */
    @Transactional("controlTransactionManager")
    fun confirmSetup(userId: UUID, code: String): Boolean {
        val secret = pendingSecrets[userId] ?: return false
        if (!verifyCode(secret, code)) return false

        val user = userRepository.findById(userId).orElse(null) ?: return false
        val (enc, iv) = cipher.encrypt(base32Encode(secret)) // guarda o segredo em Base32 cifrado
        user.totpSecretEnc = enc
        user.totpSecretIv = iv
        userRepository.save(user)

        pendingSecrets.remove(userId)
        return true
    }

    /**
     * Verifica um codigo TOTP contra o segredo ATIVO do usuario (decifrado do banco).
     * Janela de +/-1 passo (30s) para tolerar pequenas derivas de relogio.
     * Retorna false se o usuario nao tiver 2FA ativo.
     */
    @Transactional("controlTransactionManager", readOnly = true)
    fun verify(userId: UUID, code: String): Boolean {
        val user = userRepository.findById(userId).orElse(null) ?: return false
        val enc = user.totpSecretEnc ?: return false
        val iv = user.totpSecretIv ?: return false
        val secret = base32Decode(cipher.decrypt(enc, iv))
        return verifyCode(secret, code)
    }

    /**
     * Cria uma sessao intermediaria valida por 5 minutos. O sessionToken e enviado
     * ao cliente no login; o cliente o usa em /auth/2fa/verify.
     */
    fun createSession(userId: UUID): String {
        val token = UUID.randomUUID().toString()
        sessions[token] = Pair(userId, Instant.now().plusSeconds(300))
        return token
    }

    /**
     * Resolve e consome a sessao intermediaria (uso unico — remove apos leitura).
     * Retorna null se o token nao existir ou tiver expirado.
     */
    fun resolveSession(sessionToken: String): UUID? {
        val (userId, expiry) = sessions.remove(sessionToken) ?: return null
        if (Instant.now().isAfter(expiry)) return null
        return userId
    }

    // ── RFC 6238: TOTP (HMAC-SHA1, 30s, 6 digitos) ──────────────────────────

    private fun verifyCode(secret: ByteArray, code: String): Boolean {
        if (code.length != 6 || !code.all { it.isDigit() }) return false
        val counter = Instant.now().epochSecond / 30
        // janela de -1, 0, +1 para tolerar deriva de relogio
        for (delta in -1..1) {
            if (hotp(secret, counter + delta) == code) return true
        }
        return false
    }

    private fun hotp(secret: ByteArray, counter: Long): String {
        // HMAC-SHA1 do contador em big-endian 8 bytes
        val msg = ByteArray(8)
        var c = counter
        for (i in 7 downTo 0) {
            msg[i] = (c and 0xFF).toByte()
            c = c ushr 8
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val hash = mac.doFinal(msg)

        // Dynamic truncation (RFC 4226, Section 5.3)
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val otp = (
            (hash[offset].toInt() and 0x7F) shl 24 or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        ) % 10.0.pow(6).toInt()

        return otp.toString().padStart(6, '0')
    }

    // ── Base32 (RFC 4648) sem padding — compativel com Google Authenticator ──

    private val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    private fun base32Encode(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                sb.append(BASE32_ALPHABET[(buffer ushr bitsLeft) and 0x1F])
            }
        }
        if (bitsLeft > 0) {
            sb.append(BASE32_ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1F])
        }
        return sb.toString()
    }

    private fun base32Decode(encoded: String): ByteArray {
        val clean = encoded.trim().uppercase().filter { it != '=' }
        val out = ArrayList<Byte>()
        var buffer = 0
        var bitsLeft = 0
        for (ch in clean) {
            val idx = BASE32_ALPHABET.indexOf(ch)
            require(idx >= 0) { "caractere Base32 invalido" }
            buffer = (buffer shl 5) or idx
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.add(((buffer ushr bitsLeft) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }
}
