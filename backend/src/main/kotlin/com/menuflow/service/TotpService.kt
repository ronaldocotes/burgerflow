package com.menuflow.service

import com.menuflow.dto.TotpSetupResponse
import org.springframework.stereotype.Service
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
 * NOTA DE PRODUCAO: os segredos sao mantidos em memoria (ConcurrentHashMap).
 * Pendente V15 migration no banco de CONTROLE:
 *   ALTER TABLE users ADD COLUMN IF NOT EXISTS totp_secret VARCHAR(256);
 * Apos a migration, substituir secrets/pendingSecrets por leitura/escrita no
 * userRepository (criptografar o segredo em repouso — AES-256-GCM, mesmo
 * padrao das credenciais do iFood).
 *
 * As sessoes intermediarias (sessionToken -> userId, expiry) tambem ficam em
 * memoria; considerar Redis para ambientes multi-instancia.
 */
@Service
class TotpService {

    private val random = SecureRandom()

    // segredo confirmado por userId (ativo — 2FA ligado)
    private val secrets = ConcurrentHashMap<UUID, ByteArray>()

    // segredo pendente de confirmacao por userId (setup iniciado, ainda nao confirmado)
    private val pendingSecrets = ConcurrentHashMap<UUID, ByteArray>()

    // token de sessao intermediaria -> (userId, expiry)
    private val sessions = ConcurrentHashMap<String, Pair<UUID, Instant>>()

    // ── API publica ──────────────────────────────────────────────────────────

    fun hasSecret(userId: UUID): Boolean = secrets.containsKey(userId)

    /**
     * Inicia o setup: gera um segredo novo, guarda como pendente (nao ativo ainda)
     * e devolve o URI otpauth:// para o front gerar o QR code.
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
     * Confirma o setup verificando o codigo com o segredo pendente.
     * Se valido, move o segredo de pendente para ativo e retorna true.
     * Se invalido, o segredo pendente permanece (usuario pode tentar de novo).
     */
    fun confirmSetup(userId: UUID, code: String): Boolean {
        val secret = pendingSecrets[userId] ?: return false
        if (!verifyCode(secret, code)) return false
        secrets[userId] = secret
        pendingSecrets.remove(userId)
        return true
    }

    /**
     * Verifica um codigo TOTP contra o segredo ativo do usuario.
     * Janela de +/-1 passo (30s) para tolerar pequenas derivas de relogio.
     * Retorna false se o usuario nao tiver 2FA ativo.
     */
    fun verify(userId: UUID, code: String): Boolean {
        val secret = secrets[userId] ?: return false
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
}
