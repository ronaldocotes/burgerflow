package com.menuflow.ads

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/**
 * Teste unitario das ESCRITAS da Fase 8.2 SEM Spring nem HTTP real com a Meta: sobe um
 * HttpServer local (com.sun.net.httpserver) e aponta o baseUrl do MetaGraphClient para ele,
 * CAPTURANDO o corpo de cada requisicao. Prova, no payload de verdade:
 *
 *  Achado 1 (gasto acidental): createCampaign/createAdSet/createAd enviam status=PAUSED — nada
 *    roda por construcao, sem depender da premissa de hierarquia da Meta.
 *  Achado 3 (SSRF): uploadAdImage rejeita imageUrl http OU host interno ANTES de qualquer
 *    download (o servidor de imagem NUNCA e contatado).
 */
class MetaGraphClientWriteTest {

    private lateinit var server: HttpServer
    private val mapper = ObjectMapper()
    private val hits = AtomicInteger(0)
    private var lastBody: String = ""

    @AfterEach
    fun stop() {
        if (::server.isInitialized) server.stop(0)
    }

    /**
     * Sobe um server que conta chamadas, guarda o ultimo corpo recebido e devolve [json] em
     * qualquer path; retorna o MetaGraphClient apontado a ele.
     */
    private fun clientRecording(json: String): MetaGraphClient {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            hits.incrementAndGet()
            lastBody = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val bytes = json.toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        val baseUrl = "http://127.0.0.1:${server.address.port}"
        return MetaGraphClient(mapper, baseUrl, "v21.0")
    }

    private fun port(): Int = server.address.port

    @Test
    fun `createCampaign envia status PAUSED`() {
        val client = clientRecording("""{"id":"camp_1"}""")
        val id = client.createCampaign("tok", "1234567890", "Promo", "OUTCOME_TRAFFIC")
        assertEquals("camp_1", id)
        assertTrue(lastBody.contains("status=PAUSED"), "campanha deve nascer PAUSED; body=$lastBody")
    }

    @Test
    fun `createAdSet envia status PAUSED`() {
        val client = clientRecording("""{"id":"adset_1"}""")
        val id = client.createAdSet("tok", "1234567890", "camp_1", "Conjunto", 5000, -0.03, -51.07, 10)
        assertEquals("adset_1", id)
        assertTrue(lastBody.contains("status=PAUSED"), "adset deve nascer PAUSED (nao ACTIVE); body=$lastBody")
    }

    @Test
    fun `createAd envia status PAUSED`() {
        val client = clientRecording("""{"id":"ad_1"}""")
        val id = client.createAd("tok", "1234567890", "Anuncio", "adset_1", "creative_1")
        assertEquals("ad_1", id)
        assertTrue(lastBody.contains("status=PAUSED"), "ad deve nascer PAUSED (nao ACTIVE); body=$lastBody")
    }

    @Test
    fun `uploadAdImage rejeita http e NAO baixa`() {
        val client = clientRecording("""{"images":{"f":{"hash":"h"}}}""")
        // A imageUrl aponta para o PROPRIO servidor de teste: se o guard nao barrasse, o download
        // aconteceria e o contador subiria. Como e http (inseguro), deve falhar ANTES do I/O.
        val ex = assertThrows(IllegalArgumentException::class.java) {
            client.uploadAdImage("tok", "1234567890", "http://127.0.0.1:${port()}/img.jpg")
        }
        assertTrue(ex.message!!.contains("https"))
        assertEquals(0, hits.get(), "URL insegura nao pode gerar NENHUMA requisicao (download nao ocorre)")
    }

    @Test
    fun `uploadAdImage rejeita host interno loopback e NAO baixa`() {
        val client = clientRecording("""{"images":{"f":{"hash":"h"}}}""")
        // https, mas 127.0.0.1 e loopback interno -> bloqueado (mesmo com scheme valido).
        val ex = assertThrows(IllegalArgumentException::class.java) {
            client.uploadAdImage("tok", "1234567890", "https://127.0.0.1:${port()}/img.jpg")
        }
        assertTrue(ex.message!!.contains("interno") || ex.message!!.contains("privado"))
        assertEquals(0, hits.get(), "host interno nao pode gerar NENHUMA requisicao (download nao ocorre)")
    }

    @Test
    fun `uploadAdImage rejeita IP de metadata da nuvem`() {
        val client = clientRecording("""{"images":{"f":{"hash":"h"}}}""")
        val ex = assertThrows(IllegalArgumentException::class.java) {
            client.uploadAdImage("tok", "1234567890", "https://169.254.169.254/latest/meta-data")
        }
        assertTrue(ex.message!!.contains("interno") || ex.message!!.contains("privado"))
        assertEquals(0, hits.get())
    }
}
