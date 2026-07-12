package com.menuflow.dispatch

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatusCode
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriUtils
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** Coordenada geografica (graus decimais). */
data class LatLng(val lat: Double, val lng: Double)

/**
 * Geocodificacao de endereco -> coordenada, necessaria porque o ViaCEP (usado no
 * cadastro do endereco de entrega) NAO retorna lat/lng, e o despacho precisa das
 * coordenadas para calcular distancia/tarifa e ordenar por proximidade.
 *
 *  - PRIMARIO: Google Geocoding API. A chave e resolvida POR REQUISICAO pelo
 *    [GoogleApiKeyProvider] (banco de controle > env GOOGLE_ROUTES_API_KEY > vazio),
 *    em vez de capturada no boot — com o banco vazio, o valor e a env atual.
 *  - FALLBACK: null (com log de aviso) quando nao ha chave ou o endereco nao resolve.
 *    Um mapa "centro do bairro" para Macapa pode ser plugado aqui depois, sem mudar a
 *    assinatura. O chamador decide o que fazer com null (ex.: manter fee do pedido).
 *
 * Timeout curto (3s), fail-safe: qualquer erro vira null (nunca derruba o pedido).
 * A origem resolvida deve ser gravada em orders.delivery_geocode_source ("GOOGLE").
 *
 * A URI e montada com os parametros ENCODADOS (achado A3): o endereco agora e entrada
 * ANONIMA do cardapio publico, entao um endereco legitimo com '&', '=', '{', '}',
 * espacos ou acentos nao pode injetar parametros na chamada Google nem quebrar a URI.
 *
 * CACHE (achado A1 — custo externo): o cardapio publico ANONIMO chama isto a cada hit
 * (~US$0,005/geocode). Um cache por endereco NORMALIZADO corta chamadas repetidas do
 * mesmo endereco (quote->quote e quote->create). Apenas resultados RESOLVIDOS entram no
 * cache; null (sem chave / falha transitoria / sem match) NAO e cacheado, para nao
 * envenenar um endereco que passe a resolver depois.
 */
@Service
class GeocodingService(
    private val googleApiKeyProvider: GoogleApiKeyProvider,
    @Value("\${google.geocoding.base-url:https://maps.googleapis.com}") private val baseUrl: String,
    builder: RestClient.Builder,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Sem baseUrl no client: a URI absoluta e montada em buildGeocodeUri (ja encodada),
    // o que permite passar um java.net.URI e evitar a expansao de template do endereco.
    private val client: RestClient = builder
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(3000)
                setReadTimeout(3000)
            },
        )
        .build()

    // A1: cache em memoria (Caffeine, mesmo motor do CacheConfig) por endereco
    // normalizado. TTL 24h e tamanho limitado — so entradas resolvidas.
    private val geocodeCache: Cache<String, LatLng> = Caffeine.newBuilder()
        .expireAfterWrite(24, TimeUnit.HOURS)
        .maximumSize(10_000)
        .build()

    /**
     * Resolve o endereco em coordenada. Retorna null se sem chave, sem match, ou erro.
     * A origem resultante (para delivery_geocode_source) e "GOOGLE" quando resolve.
     * Consulta o cache antes de chamar o Google (A1) e so guarda resultados nao-nulos.
     */
    fun geocode(street: String?, neighborhood: String?, city: String?, zip: String?): LatLng? {
        val apiKey = googleApiKeyProvider.resolve()
        if (apiKey.isBlank()) {
            log.debug("Geocoding sem chave Google resolvida (banco/env) -- retornando null (fallback)")
            return null
        }
        val parts = listOf(street, neighborhood, city, zip).map { it?.trim().orEmpty() }
        val addressText = parts.filter { it.isNotBlank() }.joinToString(", ")
        if (addressText.isBlank()) return null

        // Chave NORMALIZADA (lowercase + trim de cada campo): "Rua A"/" rua a " colidem.
        val cacheKey = parts.joinToString("|") { it.lowercase() }
        geocodeCache.getIfPresent(cacheKey)?.let { return it }

        val resolved = fetchLatLng(addressText, apiKey) ?: return null
        geocodeCache.put(cacheKey, resolved)
        return resolved
    }

    /**
     * Chamada remota ao Google (seam isolado — nao consulta cache). Recebe a [apiKey] ja
     * resolvida pelo [geocode] (banco/env). Publico apenas para permitir o teste do cache
     * (spy/verify times(1)); em producao so [geocode] o chama.
     *
     * SEGURANCA (achado A1 do Centuriao): a chave vai no header X-Goog-Api-Key, NUNCA na
     * URL. Numa falha de I/O (timeout/DNS/conexao) o RestClient lanca ResourceAccessException
     * cuja mensagem embute a URL — se a chave estivesse na query, ela cairia no log. Alem
     * disso o catch loga apenas e.javaClass.simpleName (nunca e.message), como no
     * GoogleApiKeyProvider. Defesa em profundidade: header + log sem mensagem crua.
     */
    fun fetchLatLng(addressText: String, apiKey: String): LatLng? {
        return try {
            @Suppress("UNCHECKED_CAST")
            val body = client.get()
                .uri(buildGeocodeUri(addressText))
                .header("X-Goog-Api-Key", apiKey)
                .retrieve()
                .onStatus(HttpStatusCode::isError) { _, res ->
                    throw IllegalStateException("Geocoding HTTP ${res.statusCode}")
                }
                .body(Map::class.java) as? Map<String, Any?>
                ?: return null

            val results = body["results"] as? List<Map<String, Any?>>
            val location = ((results?.firstOrNull()?.get("geometry") as? Map<String, Any?>)
                ?.get("location") as? Map<String, Any?>) ?: return null
            val lat = (location["lat"] as? Number)?.toDouble() ?: return null
            val lng = (location["lng"] as? Number)?.toDouble() ?: return null
            LatLng(lat, lng)
        } catch (e: Exception) {
            // NUNCA logar e.message: numa falha de I/O ela embute a URL da chamada. So a classe.
            log.warn("Geocoding falhou para '{}': {}", addressText, e.javaClass.simpleName)
            null
        }
    }

    /**
     * Monta a URI absoluta do Geocoding com o endereco percent-encodado (A3). Usa
     * [UriUtils.encodeQueryParam] (tipo QUERY_PARAM), que encoda '&', '=', '+', '{',
     * '}', espacos e acentos — garantindo que o valor NUNCA vira separador/parametro.
     * URI absoluta -> o RestClient a usa como esta, sem expandir template do endereco.
     *
     * A chave NAO entra na URI (achado A1): vai no header X-Goog-Api-Key em [fetchLatLng].
     */
    internal fun buildGeocodeUri(addressText: String): URI {
        val encodedAddress = UriUtils.encodeQueryParam(addressText, StandardCharsets.UTF_8)
        return URI.create("$baseUrl/maps/api/geocode/json?address=$encodedAddress")
    }
}
