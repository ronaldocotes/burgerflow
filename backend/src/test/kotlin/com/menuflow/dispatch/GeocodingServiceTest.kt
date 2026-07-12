package com.menuflow.dispatch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.web.client.RestClient
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Testes de unidade do GeocodingService (sem Spring / sem rede):
 *  - A3: a URI do geocode encoda os parametros -> endereco anonimo com '&', '=', '{',
 *    '}', espacos e acentos NAO injeta parametro nem quebra a URI.
 *  - A1: cache por endereco normalizado -> o mesmo endereco chama o Google 1x so.
 *  - Fase 1 chaves-de-API: a chave usada vem do GoogleApiKeyProvider.resolve()
 *    (banco > env), nao mais de um @Value capturado no boot.
 */
class GeocodingServiceTest {

    // A chave agora e resolvida por requisicao pelo GoogleApiKeyProvider; mockamos o
    // provider para simular a chave vinda do "banco" (ou vazia).
    private fun service(apiKey: String = "test-key"): GeocodingService {
        val provider = Mockito.mock(GoogleApiKeyProvider::class.java)
        Mockito.`when`(provider.resolve()).thenReturn(apiKey)
        return GeocodingService(
            googleApiKeyProvider = provider,
            baseUrl = "https://maps.googleapis.com",
            builder = RestClient.builder(),
        )
    }

    // --- A3: encode + anti-injecao de query ---
    @Test
    fun `buildGeocodeUri encodes special chars and does not allow query injection`() {
        val svc = service()
        val addr = "Rua A & B, nº 10 {ap 2}, São Paulo"
        val key = "sec&ret=key"

        val uri = svc.buildGeocodeUri(addr, key) // nao pode lancar

        // A query so pode ter DOIS separadores logicos: address=... e key=... . Os '&'
        // dentro dos valores tem de estar percent-encodados (%26), senao virariam
        // parametros novos (injecao). Split pelo '&' literal -> exatamente 2 pares.
        val pairs = uri.rawQuery.split("&")
        assertEquals(2, pairs.size, "'&' no endereco/chave nao pode gerar parametros extras")
        assertTrue(pairs[0].startsWith("address="), "1o parametro deve ser address")
        assertTrue(pairs[1].startsWith("key="), "2o parametro deve ser key")

        // '&' e '=' foram encodados (aparece %26 e %3D no raw query).
        assertTrue(uri.rawQuery.contains("%26"), "'&' do valor deve virar %26")
        assertTrue(uri.rawQuery.contains("%3D"), "'=' do valor deve virar %3D")
        // '{' e '}' encodados (nao quebram a URI).
        assertTrue(uri.rawQuery.contains("%7B") && uri.rawQuery.contains("%7D"), "chaves devem ser encodadas")

        // Fidelidade: o valor decodificado bate com o endereco original (acentos/espacos).
        val decodedAddress = URLDecoder.decode(pairs[0].removePrefix("address="), StandardCharsets.UTF_8)
        assertEquals(addr, decodedAddress, "endereco deve chegar integro apos encode/decode")

        // Host/path fixos preservados (sem SSRF): a URI aponta para o Google Geocoding.
        assertEquals("maps.googleapis.com", uri.host)
        assertEquals("/maps/api/geocode/json", uri.rawPath)
    }

    // --- A1: cache por endereco normalizado -> 1 chamada remota ---
    @Test
    fun `geocode caches by normalized address and hits remote only once`() {
        val svc = Mockito.spy(service())
        Mockito.doReturn(LatLng(1.0, 2.0)).`when`(svc).fetchLatLng(Mockito.anyString(), Mockito.anyString())

        val first = svc.geocode("Rua A", "Centro", "Macapá", "68900000")
        // Mesma origem com case/espacos diferentes -> mesma chave normalizada -> cache.
        val second = svc.geocode("  rua a ", "centro", "MACAPÁ ", "68900000")

        assertEquals(LatLng(1.0, 2.0), first)
        assertEquals(first, second)
        // A 2a resolucao veio do cache: o Google foi chamado UMA vez so.
        Mockito.verify(svc, Mockito.times(1)).fetchLatLng(Mockito.anyString(), Mockito.anyString())
    }

    // --- A1/comportamento null-sem-chave preservado: sem chave nao chama remoto nem cacheia ---
    @Test
    fun `blank api key returns null and never calls remote`() {
        val svc = Mockito.spy(service(apiKey = ""))
        assertNull(svc.geocode("Rua A", "Centro", "Macapá", "68900000"))
        Mockito.verify(svc, Mockito.never()).fetchLatLng(Mockito.anyString(), Mockito.anyString())
    }

    // --- Fase 1: o consumidor usa a chave RESOLVIDA pelo provider (banco > env) ---
    @Test
    fun `geocode uses the key resolved by GoogleApiKeyProvider`() {
        // provider.resolve() simula a chave vinda do BANCO de controle (diferente da env).
        val svc = Mockito.spy(service(apiKey = "KEY-FROM-DB"))
        Mockito.doReturn(LatLng(1.0, 2.0)).`when`(svc).fetchLatLng(Mockito.anyString(), Mockito.anyString())

        svc.geocode("Rua A", "Centro", "Macapá", "68900000")

        // A chave que chega em fetchLatLng (e portanto na URI do Google) e exatamente a
        // resolvida pelo provider — prova de que o consumidor deixou de usar o @Value.
        // Verificacao com VALORES CONCRETOS (casa por equals): Mockito.eq() devolveria
        // null e o parametro Kotlin non-null estouraria NPE.
        Mockito.verify(svc).fetchLatLng("Rua A, Centro, Macapá, 68900000", "KEY-FROM-DB")
    }
}
