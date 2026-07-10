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
import java.time.Duration

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
}

/** Conta de anuncio como a Meta a devolve (subset dos campos pedidos). */
data class MetaAdAccountDto(
    val externalAccountId: String,
    val name: String?,
    val currency: String?,
    val timezoneName: String?,
)
