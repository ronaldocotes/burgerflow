package com.menuflow.platform

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Verifica a saúde das integrações externas (iFood, OpenDelivery, WAHA, LiteLLM)
 * com cache servidor de 30 segundos. Falha em um card nunca derruba os outros
 * (fail-open por card): exceções são capturadas individualmente.
 *
 * HTTP via JDK HttpClient (timeout de 2s connect+read): simples, sem conversão
 * de erros do Spring RestClient, sem dependência adicional.
 *
 * Cache: AtomicReference com timestamp — sem Spring Cache / Caffeine.
 *
 * Segurança: URLs internas (WAHA, LiteLLM) NUNCA são expostas ao cliente; apenas
 * nome/status/detail são devolvidos. Só SUPER_ADMIN alcança este endpoint.
 */
@Service
class PlatformIntegrationsHealthService(
    @Value("\${ifood.base-url:https://merchant-api.ifood.com.br}") private val ifoodBaseUrl: String,
    @Value("\${waha.base-url:http://127.0.0.1:3030}") private val wahaBaseUrl: String,
    @Value("\${litellm.url:http://127.0.0.1:4000}") private val liteLlmUrl: String,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val ttl = Duration.ofSeconds(30)

    /** Par (instante do cálculo, resultado). Null = sem cache ainda. */
    private val cache = AtomicReference<Pair<Instant, IntegrationsHealthResponse>?>()

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    fun health(): IntegrationsHealthResponse {
        val cached = cache.get()
        if (cached != null && Duration.between(cached.first, Instant.now()) < ttl) {
            return cached.second
        }
        val result = compute()
        cache.set(Pair(Instant.now(), result))
        return result
    }

    // ── Lógica de cálculo ────────────────────────────────────────────────────

    private fun compute(): IntegrationsHealthResponse {
        val cards = listOf(
            checkIfood(),
            checkOpenDelivery(),
            checkWaha(),
            checkLiteLlm(),
        )
        return IntegrationsHealthResponse(updatedAt = Instant.now(), cards = cards)
    }

    /**
     * iFood: GET /v1.0/merchants — 2xx ou 401 (token expirado mas serviço acessível)
     * mapeiam para OK; 5xx para DEGRADED; timeout/recusa/outro para DOWN.
     */
    private fun checkIfood(): IntegrationCard = try {
        when (val code = httpStatus("$ifoodBaseUrl/v1.0/merchants")) {
            null -> IntegrationCard("iFood", IntegrationStatus.DOWN, "Timeout ou conexão recusada")
            in 200..299, 401 -> IntegrationCard("iFood", IntegrationStatus.OK)
            in 500..599 -> IntegrationCard("iFood", IntegrationStatus.DEGRADED, "HTTP $code")
            else -> IntegrationCard("iFood", IntegrationStatus.DOWN, "HTTP $code")
        }
    } catch (e: Exception) {
        log.warn("[integrations-health] iFood check falhou: {}", e.message)
        IntegrationCard("iFood", IntegrationStatus.DOWN, e.message?.take(80))
    }

    /**
     * OpenDelivery (99Food/Rappi): cliente HTTP ainda não implementado (Fase 5.5).
     * Retorna DOWN com detalhe explicativo sem lançar exceção.
     */
    private fun checkOpenDelivery(): IntegrationCard =
        IntegrationCard("OpenDelivery", IntegrationStatus.DOWN, "Não configurado")

    /**
     * WAHA: GET /api/sessions — OK se ≥1 sessão com status WORKING,
     * DEGRADED se há sessões mas nenhuma WORKING, DOWN se timeout/erro.
     */
    private fun checkWaha(): IntegrationCard = try {
        val body = httpBody("$wahaBaseUrl/api/sessions")
            ?: return IntegrationCard("WAHA", IntegrationStatus.DOWN, "Timeout ou conexão recusada")
        val sessions = objectMapper.readTree(body)
        val total = sessions.size()
        val working = sessions.count { node -> node["status"]?.asText() == "WORKING" }
        when {
            total == 0 -> IntegrationCard("WAHA", IntegrationStatus.DOWN, "Sem sessões registradas")
            working >= 1 -> IntegrationCard("WAHA", IntegrationStatus.OK, "$working/$total WORKING")
            else -> IntegrationCard("WAHA", IntegrationStatus.DEGRADED, "0/$total WORKING")
        }
    } catch (e: Exception) {
        log.warn("[integrations-health] WAHA check falhou: {}", e.message)
        IntegrationCard("WAHA", IntegrationStatus.DOWN, e.message?.take(80))
    }

    /**
     * LiteLLM: GET /health/liveliness — 200 = OK, qualquer outro = DOWN.
     */
    private fun checkLiteLlm(): IntegrationCard = try {
        when (val code = httpStatus("$liteLlmUrl/health/liveliness")) {
            200 -> IntegrationCard("LiteLLM", IntegrationStatus.OK)
            null -> IntegrationCard("LiteLLM", IntegrationStatus.DOWN, "Timeout")
            else -> IntegrationCard("LiteLLM", IntegrationStatus.DOWN, "HTTP $code")
        }
    } catch (e: Exception) {
        log.warn("[integrations-health] LiteLLM check falhou: {}", e.message)
        IntegrationCard("LiteLLM", IntegrationStatus.DOWN, e.message?.take(80))
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────

    /** Retorna o status HTTP ou null em timeout/conexão recusada/erro de rede. */
    private fun httpStatus(url: String): Int? = try {
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .timeout(Duration.ofSeconds(2))
            .build()
        http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
    } catch (_: Exception) {
        null
    }

    /** Retorna o corpo HTTP como String ou null em timeout/erro. */
    private fun httpBody(url: String): String? = try {
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .timeout(Duration.ofSeconds(2))
            .build()
        http.send(req, HttpResponse.BodyHandlers.ofString()).body()
    } catch (_: Exception) {
        null
    }
}
