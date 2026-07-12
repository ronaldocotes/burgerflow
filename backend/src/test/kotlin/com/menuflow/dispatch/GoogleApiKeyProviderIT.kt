package com.menuflow.dispatch

import com.menuflow.IntegrationTestBase
import com.menuflow.crypto.SecretCipher
import com.menuflow.model.control.PlatformApiKey
import com.menuflow.model.control.PlatformApiKeyProviderType
import com.menuflow.repository.control.PlatformApiKeyRepository
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Integracao com o BANCO DE CONTROLE real (Testcontainers): platform_api_key +
 * SecretCipher + GoogleApiKeyProvider. Prova, contra Postgres de verdade:
 *  - precedencia banco > env (com env setada em ENV-FALLBACK-KEY);
 *  - fallback para a env quando nao ha linha ativa;
 *  - o valor persistido e BYTEA cifrado (nao texto), e decifra de volta ao original;
 *  - evict: apos invalidate(), uma mudanca no banco passa a valer (cache nao serve velho);
 *  - erro de decifra nao estoura e cai na env.
 *
 * A env google.routes.api-key e forcada aqui (padrao do LoginRateLimitTest) para
 * provar "banco != env".
 */
class GoogleApiKeyProviderIT @Autowired constructor(
    private val provider: GoogleApiKeyProvider,
    private val repository: PlatformApiKeyRepository,
    private val cipher: SecretCipher,
) : IntegrationTestBase() {

    companion object {
        const val ENV_KEY = "ENV-FALLBACK-KEY"

        @JvmStatic
        @DynamicPropertySource
        fun envKey(registry: DynamicPropertyRegistry) {
            registry.add("google.routes.api-key") { ENV_KEY }
        }
    }

    @BeforeEach
    fun reset() {
        repository.deleteAll()
        provider.invalidate()
    }

    private fun saveActive(plain: String): PlatformApiKey {
        val (enc, iv) = cipher.encrypt(plain)
        return repository.save(
            PlatformApiKey(provider = PlatformApiKeyProviderType.GOOGLE_MAPS, valueEnc = enc, valueIv = iv),
        )
    }

    @Test
    fun `linha ativa no banco tem precedencia sobre a env`() {
        saveActive("KEY-DB-REAL")
        provider.invalidate()

        assertEquals("KEY-DB-REAL", provider.resolve())
        assertEquals(GoogleKeySource.DB, provider.source())
        assertFalse(provider.resolve() == ENV_KEY, "a chave do banco deve prevalecer sobre a env")
    }

    @Test
    fun `banco vazio cai na env (comportamento atual de producao preservado)`() {
        // Sem linha ativa (reset ja limpou).
        provider.invalidate()

        assertEquals(ENV_KEY, provider.resolve())
        assertEquals(GoogleKeySource.ENV, provider.source())
    }

    @Test
    fun `valor persistido e BYTEA cifrado e decifra de volta ao original`() {
        val saved = saveActive("KEY-BYTEA-123")
        val reloaded = repository.findById(saved.id!!).orElseThrow()

        // NAO e o texto claro em bytes.
        assertFalse(
            reloaded.valueEnc.contentEquals("KEY-BYTEA-123".toByteArray(Charsets.UTF_8)),
            "value_enc precisa ser ciphertext, nunca o texto claro",
        )
        // Decifra de volta ao original com o mesmo cipher (round-trip via banco).
        assertEquals("KEY-BYTEA-123", cipher.decrypt(reloaded.valueEnc, reloaded.valueIv))
        // IV tem 12 bytes (GCM).
        assertEquals(12, reloaded.valueIv.size)
        assertArrayEquals(saved.valueIv, reloaded.valueIv)
    }

    @Test
    fun `evict apos invalidate faz a mudanca no banco valer`() {
        val row = saveActive("KEY-OLD")
        provider.invalidate()
        assertEquals("KEY-OLD", provider.resolve()) // popula o cache

        // Atualiza a MESMA linha ativa para um novo valor.
        val (enc, iv) = cipher.encrypt("KEY-NEW")
        row.valueEnc = enc
        row.valueIv = iv
        repository.save(row)

        // Sem invalidate: o cache ainda serve o valor antigo (TTL 5min).
        assertEquals("KEY-OLD", provider.resolve())

        // Apos invalidate (o que a Fase 2 fara no save/rotate): passa a valer o novo.
        provider.invalidate()
        assertEquals("KEY-NEW", provider.resolve())
    }

    @Test
    fun `erro de decifra nao estoura e cai na env`() {
        // Grava uma linha com ciphertext/IV incoerentes: decrypt vai lancar.
        repository.save(
            PlatformApiKey(
                provider = PlatformApiKeyProviderType.GOOGLE_MAPS,
                valueEnc = ByteArray(16) { 0x07 },
                valueIv = ByteArray(12) { 0x09 },
            ),
        )
        provider.invalidate()

        assertDoesNotThrow {
            assertEquals(ENV_KEY, provider.resolve())
            assertEquals(GoogleKeySource.ENV, provider.source())
        }
    }
}
