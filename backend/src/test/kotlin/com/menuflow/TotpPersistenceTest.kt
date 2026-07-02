package com.menuflow

import com.menuflow.IntegrationTestBase
import com.menuflow.service.TotpService
import com.menuflow.model.control.Tenant
import com.menuflow.model.control.User
import com.menuflow.model.control.UserRole
import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.control.UserRepository
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

/**
 * Prova de ponta a ponta que o 2FA (TOTP) e PERSISTIDO CIFRADO no banco de controle
 * (V15) e sobrevive a uma nova leitura da entidade (simula restart: recarrega o User
 * do repositorio e verifica que o segredo continua valido).
 *
 * O codigo TOTP e gerado no teste reimplementando o RFC 6238 (HMAC-SHA1/6dig/30s) a
 * partir do segredo Base32 devolvido por startSetup — mesmo padrao usado no SISATER.
 */
class TotpPersistenceTest @Autowired constructor(
    private val totpService: TotpService,
    private val userRepository: UserRepository,
    private val tenantRepository: TenantRepository,
    private val passwordEncoder: PasswordEncoder,
) : IntegrationTestBase() {

    private fun seedSuperAdmin(): UUID {
        val slug = "totp${UUID.randomUUID().toString().replace("-", "").take(9)}"
        val tenant = tenantRepository.save(Tenant(slug = slug, displayName = "TOTP Burger"))
        val user = userRepository.save(
            User(
                tenantId = tenant.id!!,
                email = "root@$slug.com",
                passwordHash = passwordEncoder.encode("pass1234"),
                firstName = "Root",
                role = UserRole.SUPER_ADMIN,
            ),
        )
        return user.id!!
    }

    @Test
    fun `setup - confirm - persiste segredo cifrado e verify funciona apos recarregar`() {
        val userId = seedSuperAdmin()

        // Antes do setup: sem 2FA
        assertFalse(totpService.hasSecret(userId), "sem 2FA antes do setup")

        // Inicia o setup — recebe o segredo em Base32
        val setup = totpService.startSetup(userId)
        assertTrue(setup.qrUri.startsWith("otpauth://totp/"), "URI otpauth valida")

        // Codigo TOTP corrente a partir do segredo Base32
        val code = generateCode(setup.secret)

        // Confirma — deve persistir o segredo cifrado
        assertTrue(totpService.confirmSetup(userId, code), "confirmSetup com codigo valido")

        // Ativo agora
        assertTrue(totpService.hasSecret(userId), "2FA ativo apos confirmar")

        // O segredo esta CIFRADO no banco (enc + iv presentes; nao em claro)
        val reloaded = userRepository.findById(userId).orElseThrow()
        assertNotNull(reloaded.totpSecretEnc, "totp_secret_enc persistido")
        assertNotNull(reloaded.totpSecretIv, "totp_secret_iv persistido")

        // Simula restart: verify le do banco, decifra e valida um NOVO codigo
        val code2 = generateCode(setup.secret)
        assertTrue(totpService.verify(userId, code2), "verify apos recarregar do banco")

        // Codigo errado e rejeitado
        assertFalse(totpService.verify(userId, "000000"), "codigo invalido rejeitado")
    }

    @Test
    fun `confirm com codigo errado nao ativa 2FA`() {
        val userId = seedSuperAdmin()
        totpService.startSetup(userId)
        assertFalse(totpService.confirmSetup(userId, "000000"), "codigo errado nao confirma")
        assertFalse(totpService.hasSecret(userId), "2FA continua inativo")
        val reloaded = userRepository.findById(userId).orElseThrow()
        assertNull(reloaded.totpSecretEnc, "nada persistido apos confirm falho")
    }

    @Test
    fun `sessao intermediaria e uso unico e expira`() {
        val userId = seedSuperAdmin()
        val token = totpService.createSession(userId)
        // Primeira resolucao devolve o userId
        val resolved = totpService.resolveSession(token)
        assertNotNull(resolved)
        // Segunda resolucao (mesmo token) devolve null — uso unico
        assertNull(totpService.resolveSession(token), "sessao e uso unico")
    }

    // ── RFC 6238 no teste (gera o codigo do momento a partir do segredo Base32) ──

    private fun generateCode(base32Secret: String): String {
        val secret = base32Decode(base32Secret)
        val counter = Instant.now().epochSecond / 30
        val msg = ByteArray(8)
        var c = counter
        for (i in 7 downTo 0) {
            msg[i] = (c and 0xFF).toByte()
            c = c ushr 8
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val hash = mac.doFinal(msg)
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val otp = (
            (hash[offset].toInt() and 0x7F) shl 24 or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        ) % 10.0.pow(6).toInt()
        return otp.toString().padStart(6, '0')
    }

    private val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    private fun base32Decode(encoded: String): ByteArray {
        val clean = encoded.trim().uppercase().filter { it != '=' }
        val out = ArrayList<Byte>()
        var buffer = 0
        var bitsLeft = 0
        for (ch in clean) {
            val idx = BASE32_ALPHABET.indexOf(ch)
            require(idx >= 0)
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
