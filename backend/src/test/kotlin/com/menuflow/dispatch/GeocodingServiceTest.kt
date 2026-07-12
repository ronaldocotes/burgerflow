package com.menuflow.dispatch

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.slf4j.LoggerFactory
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/**
 * Testes de unidade do GeocodingService (sem Spring):
 *  - A3: a URI do geocode encoda o endereco -> endereco anonimo com '&', '=', '{',
 *    '}', espacos e acentos NAO injeta parametro nem quebra a URI.
 *  - A1 (cache): o mesmo endereco normalizado chama o Google 1x so.
 *  - A1 (segredo, achado do Centuriao): a chave vai no header X-Goog-Api-Key, NUNCA na
 *    URL, e NUNCA aparece no log mesmo numa falha de I/O.
 *  - Fase 1 chaves-de-API: a chave usada vem do GoogleApiKeyProvider.resolve().
 *
 * NB: o GeocodingService define um requestFactory proprio (timeout 3s), o que impede o
 * MockRestServiceServer (ele seria sobrescrito). Por isso os testes de rede usam um
 * HttpServer local efemero (127.0.0.1:0) — hermetico, sem internet.
 */
class GeocodingServiceTest {

    // A chave agora e resolvida por requisicao pelo GoogleApiKeyProvider; mockamos o
    // provider para simular a chave vinda do "banco" (ou vazia).
    private fun service(
        apiKey: String = "test-key",
        baseUrl: String = "https://maps.googleapis.com",
    ): GeocodingService {
        val provider = Mockito.mock(GoogleApiKeyProvider::class.java)
        Mockito.`when`(provider.resolve()).thenReturn(apiKey)
        return GeocodingService(
            googleApiKeyProvider = provider,
            baseUrl = baseUrl,
            builder = RestClient.builder(),
        )
    }

    // --- A3: encode + anti-injecao de query (chave NAO entra mais na URI) ---
    @Test
    fun `buildGeocodeUri encodes address and does not carry the key`() {
        val svc = service()
        // Endereco com '&', '=', '{', '}', acentos e espacos — todos devem ser encodados.
        val addr = "Rua A & B = C, nº 10 {ap 2}, São Paulo"

        val uri = svc.buildGeocodeUri(addr) // nao pode lancar

        // A query so tem UM separador logico agora: address=... (a chave vai no header).
        val pairs = uri.rawQuery.split("&")
        assertEquals(1, pairs.size, "'&' no endereco nao pode gerar parametros extras")
        assertTrue(pairs[0].startsWith("address="), "unico parametro deve ser address")
        // A chave NUNCA aparece na URI (achado A1).
        assertFalse(uri.toString().contains("key="), "a chave nao pode ir na URL")

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

    // --- A1 (segredo): chave no header X-Goog-Api-Key, ausente da URL ---
    @Test
    fun `fetchLatLng envia a chave no header X-Goog-Api-Key e nao na URL`() {
        val secretKey = "AIzaSUPERSECRETKEY9999"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val capturedHeader = AtomicReference<String?>()
        val capturedUri = AtomicReference<String?>()
        server.createContext("/maps/api/geocode/json") { ex ->
            capturedHeader.set(ex.requestHeaders.getFirst("X-Goog-Api-Key"))
            capturedUri.set(ex.requestURI.toString())
            val bytes = """{"results":[{"geometry":{"location":{"lat":1.0,"lng":2.0}}}]}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val svc = service(apiKey = secretKey, baseUrl = "http://127.0.0.1:${server.address.port}")
            val result = svc.fetchLatLng("Rua A", secretKey)

            assertEquals(LatLng(1.0, 2.0), result)
            // A chave chega no HEADER, exatamente como resolvida.
            assertEquals(secretKey, capturedHeader.get(), "a chave deve ir no header X-Goog-Api-Key")
            // E NUNCA na URL (nem como key=, nem o valor em lugar nenhum da query).
            assertFalse(capturedUri.get()!!.contains("key="), "a URL nao pode ter parametro key")
            assertFalse(capturedUri.get()!!.contains(secretKey), "a URL nao pode conter a chave")
        } finally {
            server.stop(0)
        }
    }

    // --- A1 (segredo): falha de I/O NAO vaza a chave no log ---
    @Test
    fun `falha de I-O no geocode nao vaza a chave em nenhum log`() {
        val secretKey = "AIzaSECRETLEAKTEST7777"
        // baseUrl aponta para uma porta fechada -> conexao recusada -> ResourceAccessException,
        // cuja mensagem embute a URL. Como a chave vai no header e o catch NAO loga e.message,
        // a chave nao pode aparecer em log algum.
        val svc = service(apiKey = secretKey, baseUrl = "http://127.0.0.1:1")

        val logger = LoggerFactory.getLogger(GeocodingService::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        logger.level = Level.DEBUG

        try {
            val result = svc.fetchLatLng("Rua A", secretKey)
            assertNull(result, "falha de I/O deve virar null (fail-safe)")

            val allLogs = appender.list.joinToString("\n") {
                it.formattedMessage + " " + (it.throwableProxy?.message ?: "")
            }
            assertFalse(allLogs.contains(secretKey), "a chave NUNCA pode aparecer no log: $allLogs")
            assertTrue(
                appender.list.any { it.formattedMessage.contains("Geocoding falhou") },
                "deve logar a falha (sem a chave)",
            )
        } finally {
            logger.detachAppender(appender)
        }
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
