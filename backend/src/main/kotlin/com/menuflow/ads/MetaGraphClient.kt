package com.menuflow.ads

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.LocalDate

/**
 * Cliente minimo da Meta Graph API para a Fase 8.0 (read-only). So conhece um
 * endpoint: GET /me/adaccounts, usado para VALIDAR o System User Token colado pelo
 * restaurante e descobrir quais contas de anuncio ele controla.
 *
 * HTTP via JDK HttpClient (timeout connect+read), mesmo padrao simples do
 * PlatformIntegrationsHealthService — sem RestClient/dependencia extra.
 *
 * Versao da Graph API fixada em v21.0 (config meta.graph.api-version): a Meta
 * exige versao explicita na URL e deprecia versoes antigas; fixar evita quebra
 * silenciosa quando o "default" da Meta avanca. Bump e mecanico.
 *
 * Seguranca: o token vai no header Authorization: Bearer (nunca na query string, que
 * vaza em logs/proxies). NUNCA logar o token nem o corpo bruto de erro cru.
 */
@Component
class MetaGraphClient(
    private val objectMapper: ObjectMapper,
    @Value("\${meta.graph.base-url:https://graph.facebook.com}") private val baseUrl: String,
    @Value("\${meta.graph.api-version:v21.0}") private val apiVersion: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    /**
     * Lista as contas de anuncio que o token controla. Valida o token de quebra:
     *  - 2xx -> parseia data[] em [MetaAdAccountDto];
     *  - erro com code 190 (OAuthException) -> [MetaTokenInvalidException];
     *  - qualquer outra falha (rede/timeout/5xx/erro Graph) -> [MetaGraphException].
     */
    fun fetchAdAccounts(accessToken: String): List<MetaAdAccountDto> {
        val fields = URLEncoder.encode("account_id,name,currency,timezone_name", StandardCharsets.UTF_8)
        val url = "$baseUrl/$apiVersion/me/adaccounts?fields=$fields"

        val response = try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer $accessToken")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build()
            http.send(req, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            log.warn("[meta-graph] falha de rede ao chamar /me/adaccounts: {}", e.message)
            throw MetaGraphException("Nao foi possivel contatar a Meta: ${e.message}")
        }

        val root: JsonNode = try {
            objectMapper.readTree(response.body())
        } catch (e: Exception) {
            throw MetaGraphException("Resposta invalida da Meta (HTTP ${response.statusCode()})")
        }

        if (response.statusCode() !in 200..299) {
            val error = root.get("error")
            val code = error?.get("code")?.asInt()
            val message = error?.get("message")?.asText() ?: "HTTP ${response.statusCode()}"
            // 190 = token invalido/expirado/revogado (OAuthException). Distinto para o
            // service devolver um 400 claro ("reconecte") em vez de um 503 generico.
            if (code == 190) throw MetaTokenInvalidException(message)
            log.warn("[meta-graph] erro Graph code={} status={}", code, response.statusCode())
            throw MetaGraphException("A Meta recusou a requisicao: $message")
        }

        val data = root.get("data") ?: return emptyList()
        return data.mapNotNull { node ->
            val externalId = node.get("account_id")?.asText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MetaAdAccountDto(
                externalAccountId = externalId,
                name = node.get("name")?.asText(),
                currency = node.get("currency")?.asText(),
                timezoneName = node.get("timezone_name")?.asText(),
            )
        }
    }

    /**
     * Insights DIARIOS a nivel de CONTA (Fase 8.1). Chama
     * GET /act_{id}/insights?level=account&time_increment=1&time_range={since,until}
     * e devolve UMA linha por dia do intervalo (por causa de time_increment=1). Agrega
     * TODAS as campanhas da conta — inclusive as que o restaurante ja roda direto no Meta
     * Ads Manager (o valor desta fase, antes de existir campanha propria).
     *
     * Tratamento de erro (mesmo espirito do fetchAdAccounts):
     *  - code 190 -> [MetaTokenInvalidException] (o service marca a conta EXPIRED);
     *  - rate-limit (code 613, subcode 80004, ou HTTP 429) -> [MetaRateLimitException]
     *    (o job so PULA aquela conta neste tick, sem derrubar a varredura);
     *  - rede/timeout/5xx/erro Graph -> [MetaGraphException].
     *
     * Conta sem gasto no periodo devolve data:[] -> lista vazia (zero, nao erro).
     *
     * Dinheiro: spend/cpc vem como string decimal na moeda da conta -> convertidos para
     * centavos com BigDecimal (HALF_UP), NUNCA float. ctr vem como string percentual ->
     * guardado como ctr_milli (CTR% * 1000, ex.: "1.5" -> 1500).
     */
    fun fetchAccountInsights(
        accessToken: String,
        externalAccountId: String,
        since: LocalDate,
        until: LocalDate,
    ): List<MetaInsightDto> {
        val fields = URLEncoder.encode("spend,impressions,reach,clicks,ctr,cpc", StandardCharsets.UTF_8)
        // externalAccountId e guardado SEM o prefixo (AdAccount.kt / V58); a Graph API
        // exige "act_" no path do node.
        val actId = if (externalAccountId.startsWith("act_")) externalAccountId else "act_$externalAccountId"
        val timeRangeJson = """{"since":"$since","until":"$until"}"""
        val timeRange = URLEncoder.encode(timeRangeJson, StandardCharsets.UTF_8)
        val url = "$baseUrl/$apiVersion/$actId/insights" +
            "?level=account&time_increment=1&fields=$fields&time_range=$timeRange"

        val response = try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer $accessToken")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build()
            http.send(req, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            log.warn("[meta-graph] falha de rede ao chamar /insights: {}", e.message)
            throw MetaGraphException("Nao foi possivel contatar a Meta: ${e.message}")
        }

        val root: JsonNode = try {
            objectMapper.readTree(response.body())
        } catch (e: Exception) {
            throw MetaGraphException("Resposta invalida da Meta (HTTP ${response.statusCode()})")
        }

        if (response.statusCode() !in 200..299) {
            val error = root.get("error")
            val code = error?.get("code")?.asInt()
            val subcode = error?.get("error_subcode")?.asInt()
            val message = error?.get("message")?.asText() ?: "HTTP ${response.statusCode()}"
            if (code == 190) throw MetaTokenInvalidException(message)
            // Rate-limit / Business Use Case throttling: nao e erro do usuario, e transitorio.
            // Pular so esta conta neste tick (o job trata) evita estourar a varredura.
            if (code == 613 || subcode == 80004 || response.statusCode() == 429) {
                log.warn("[meta-graph] rate-limit em /insights code={} subcode={}", code, subcode)
                throw MetaRateLimitException("Meta limitou a taxa de chamadas: $message")
            }
            log.warn("[meta-graph] erro Graph /insights code={} status={}", code, response.statusCode())
            throw MetaGraphException("A Meta recusou a requisicao: $message")
        }

        val data = root.get("data") ?: return emptyList()
        return data.mapNotNull { node ->
            val date = node.get("date_start")?.asText()?.takeIf { it.isNotBlank() }
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
            MetaInsightDto(
                date = date,
                spendCents = toCents(node.get("spend")?.asText()),
                impressions = toLong(node.get("impressions")?.asText()),
                reach = toLong(node.get("reach")?.asText()),
                clicks = toLong(node.get("clicks")?.asText()),
                ctrMilli = toMilli(node.get("ctr")?.asText()),
                cpcCents = toCents(node.get("cpc")?.asText()),
            )
        }
    }

    /** "12.34" -> 1234 centavos (HALF_UP). null/vazio/malformado -> 0. Nunca float. */
    private fun toCents(raw: String?): Long =
        raw?.takeIf { it.isNotBlank() }
            ?.let { runCatching { BigDecimal(it).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong() }.getOrNull() }
            ?: 0L

    /** "1.5" (CTR%) -> 1500 (CTR% * 1000, HALF_UP). null/vazio/malformado -> 0. */
    private fun toMilli(raw: String?): Int =
        raw?.takeIf { it.isNotBlank() }
            ?.let { runCatching { BigDecimal(it).movePointRight(3).setScale(0, RoundingMode.HALF_UP).toInt() }.getOrNull() }
            ?: 0

    /** Inteiros (impressions/reach/clicks) que a Meta manda como string. Malformado -> 0. */
    private fun toLong(raw: String?): Long =
        raw?.takeIf { it.isNotBlank() }
            ?.let { runCatching { BigDecimal(it).setScale(0, RoundingMode.HALF_UP).toLong() }.getOrNull() }
            ?: 0L
}

/** Conta de anuncio como a Meta a devolve (subset dos campos pedidos). */
data class MetaAdAccountDto(
    val externalAccountId: String,
    val name: String?,
    val currency: String?,
    val timezoneName: String?,
)

/**
 * Uma linha diaria de insights (nivel conta) ja normalizada: dinheiro em centavos na
 * moeda da conta, CTR em milesimos de ponto percentual. Sem float.
 */
data class MetaInsightDto(
    val date: LocalDate,
    val spendCents: Long,
    val impressions: Long,
    val reach: Long,
    val clicks: Long,
    val ctrMilli: Int,
    val cpcCents: Long,
)
