package com.menuflow.dispatch

import com.menuflow.crypto.SecretCipher
import com.menuflow.model.control.PlatformApiKey
import com.menuflow.model.control.PlatformApiKeyProviderType
import com.menuflow.repository.control.PlatformApiKeyRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.util.Base64

/**
 * Testes de unidade do GoogleApiKeyProvider (sem Spring / sem banco real): provam a
 * LOGICA DE PRECEDENCIA (banco > env > vazio) e o fallback blindado, com o
 * repositorio mockado e um SecretCipher real. A parte "banco real BYTEA + evict"
 * fica no GoogleApiKeyProviderIT (Testcontainers).
 */
class GoogleApiKeyProviderTest {

    private val testKeyBase64 = Base64.getEncoder().encodeToString(ByteArray(32) { 'A'.code.toByte() })
    private val cipher = SecretCipher(testKeyBase64)

    private fun repoReturning(key: PlatformApiKey?): PlatformApiKeyRepository {
        val repo = Mockito.mock(PlatformApiKeyRepository::class.java)
        Mockito.`when`(repo.findFirstByProviderAndActiveTrue(PlatformApiKeyProviderType.GOOGLE_MAPS))
            .thenReturn(key)
        return repo
    }

    private fun rowFor(plain: String): PlatformApiKey {
        val (enc, iv) = cipher.encrypt(plain)
        return PlatformApiKey(provider = PlatformApiKeyProviderType.GOOGLE_MAPS, valueEnc = enc, valueIv = iv)
    }

    @Test
    fun `linha no banco tem precedencia sobre a env`() {
        val provider = GoogleApiKeyProvider(repoReturning(rowFor("KEY-DB")), cipher, envKey = "KEY-ENV")
        assertEquals("KEY-DB", provider.resolve())
        assertEquals(GoogleKeySource.DB, provider.source())
    }

    @Test
    fun `banco vazio devolve a env (comportamento atual de producao)`() {
        val provider = GoogleApiKeyProvider(repoReturning(null), cipher, envKey = "KEY-ENV")
        assertEquals("KEY-ENV", provider.resolve())
        assertEquals(GoogleKeySource.ENV, provider.source())
    }

    @Test
    fun `banco e env vazios devolvem string vazia (Haversine null como hoje)`() {
        val provider = GoogleApiKeyProvider(repoReturning(null), cipher, envKey = "")
        assertEquals("", provider.resolve())
        assertEquals(GoogleKeySource.NONE, provider.source())
    }

    @Test
    fun `erro de decifra nao estoura e cai no fallback env`() {
        // Linha com IV/ciphertext incoerentes -> decrypt lanca; o provider deve cair na env.
        val corrupt = PlatformApiKey(
            provider = PlatformApiKeyProviderType.GOOGLE_MAPS,
            valueEnc = ByteArray(16) { 0x01 },
            valueIv = ByteArray(12) { 0x02 },
        )
        val provider = GoogleApiKeyProvider(repoReturning(corrupt), cipher, envKey = "KEY-ENV")
        assertEquals("KEY-ENV", provider.resolve())
        assertEquals(GoogleKeySource.ENV, provider.source())
    }

    @Test
    fun `falha ao consultar o banco nao estoura e cai no fallback env`() {
        val repo = Mockito.mock(PlatformApiKeyRepository::class.java)
        Mockito.`when`(repo.findFirstByProviderAndActiveTrue(PlatformApiKeyProviderType.GOOGLE_MAPS))
            .thenThrow(RuntimeException("db down"))
        val provider = GoogleApiKeyProvider(repo, cipher, envKey = "KEY-ENV")
        assertEquals("KEY-ENV", provider.resolve())
    }

    // B1 (guarda): describe/resolve da Fase 2 assumem 1 provider (GOOGLE_MAPS). Este teste
    // QUEBRA de proposito ao adicionar o 2o valor no enum, forcando parametrizar a resolucao
    // por provider antes de expor a mascara de um provedor no lugar de outro.
    @Test
    fun `enum de provider tem apenas GOOGLE_MAPS (guarda B1 do Centuriao)`() {
        assertEquals(
            1,
            PlatformApiKeyProviderType.entries.size,
            "Ao adicionar um 2o provider, parametrize describe()/resolve() por provider (hoje sao GOOGLE_MAPS-only)",
        )
    }
}
