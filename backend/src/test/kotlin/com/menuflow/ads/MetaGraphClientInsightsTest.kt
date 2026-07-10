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
import java.time.LocalDate

/**
 * Teste unitario do parsing/conversao de fetchAccountInsights SEM Spring nem HTTP real:
 * sobe um HttpServer local (com.sun.net.httpserver) e aponta o baseUrl do MetaGraphClient
 * para ele. Prova o contrato critico da Fase 8.1:
 *  - dinheiro string decimal -> centavos com BigDecimal (nunca float): "12.34" -> 1234;
 *  - CTR percentual -> ctr_milli: "1.5" -> 1500;
 *  - data:[] (conta sem gasto) -> lista vazia, nao erro;
 *  - erro code 190 -> MetaTokenInvalidException; rate-limit 613 -> MetaRateLimitException.
 */
class MetaGraphClientInsightsTest {

    private lateinit var server: HttpServer
    private val mapper = ObjectMapper()

    @AfterEach
    fun stop() {
        if (::server.isInitialized) server.stop(0)
    }

    /** Sobe um server que responde [status] + [json] em qualquer path e devolve o MetaGraphClient apontado a ele. */
    private fun clientReturning(status: Int, json: String): MetaGraphClient {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val bytes = json.toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        val baseUrl = "http://127.0.0.1:${server.address.port}"
        return MetaGraphClient(mapper, baseUrl, "v21.0")
    }

    @Test
    fun `converte spend e cpc string decimal para centavos e ctr para milesimos sem float`() {
        val json = """
            {"data":[
              {"date_start":"2026-07-09","date_stop":"2026-07-09","spend":"12.34",
               "impressions":"1000","reach":"800","clicks":"50","ctr":"1.5","cpc":"0.25"}
            ]}
        """.trimIndent()
        val client = clientReturning(200, json)

        val rows = client.fetchAccountInsights("tok", "1234567890", LocalDate.parse("2026-07-09"), LocalDate.parse("2026-07-10"))

        assertEquals(1, rows.size)
        val r = rows.first()
        assertEquals(LocalDate.parse("2026-07-09"), r.date)
        assertEquals(1234L, r.spendCents, "\"12.34\" deve virar 1234 centavos (BigDecimal, sem float)")
        assertEquals(25L, r.cpcCents, "\"0.25\" deve virar 25 centavos")
        assertEquals(1500, r.ctrMilli, "\"1.5\" (CTR%) deve virar 1500 milesimos")
        assertEquals(1000L, r.impressions)
        assertEquals(800L, r.reach)
        assertEquals(50L, r.clicks)
    }

    @Test
    fun `arredonda centavos com HALF_UP`() {
        val json = """{"data":[{"date_start":"2026-07-09","spend":"9.999","ctr":"0.0005","cpc":"0"}]}"""
        val client = clientReturning(200, json)

        val r = client.fetchAccountInsights("tok", "1", LocalDate.parse("2026-07-09"), LocalDate.parse("2026-07-09")).first()

        assertEquals(1000L, r.spendCents, "9.999 -> 999.9 centavos -> 1000 (HALF_UP)")
        assertEquals(1, r.ctrMilli, "0.0005% -> 0.5 milesimo -> 1 (HALF_UP)")
        assertEquals(0L, r.cpcCents)
    }

    @Test
    fun `conta sem gasto retorna data vazia como lista vazia (nao erro)`() {
        val client = clientReturning(200, """{"data":[]}""")

        val rows = client.fetchAccountInsights("tok", "1", LocalDate.parse("2026-07-09"), LocalDate.parse("2026-07-10"))

        assertTrue(rows.isEmpty())
    }

    @Test
    fun `code 190 vira MetaTokenInvalidException`() {
        val client = clientReturning(400, """{"error":{"code":190,"message":"Invalid OAuth access token"}}""")

        assertThrows(MetaTokenInvalidException::class.java) {
            client.fetchAccountInsights("tok", "1", LocalDate.parse("2026-07-09"), LocalDate.parse("2026-07-10"))
        }
    }

    @Test
    fun `rate-limit code 613 vira MetaRateLimitException`() {
        val client = clientReturning(400, """{"error":{"code":613,"message":"Calls to this api have exceeded the rate limit"}}""")

        assertThrows(MetaRateLimitException::class.java) {
            client.fetchAccountInsights("tok", "1", LocalDate.parse("2026-07-09"), LocalDate.parse("2026-07-10"))
        }
    }
}
