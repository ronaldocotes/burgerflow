package com.menuflow.dispatch

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder

/** Coordenada geografica (graus decimais). */
data class LatLng(val lat: Double, val lng: Double)

/**
 * Geocodificacao de endereco -> coordenada, necessaria porque o ViaCEP (usado no
 * cadastro do endereco de entrega) NAO retorna lat/lng, e o despacho precisa das
 * coordenadas para calcular distancia/tarifa e ordenar por proximidade.
 *
 *  - PRIMARIO: Google Geocoding API (reaproveita a chave do Routes, GOOGLE_ROUTES_API_KEY).
 *  - FALLBACK: null (com log de aviso) quando nao ha chave ou o endereco nao resolve.
 *    Um mapa "centro do bairro" para Macapa pode ser plugado aqui depois, sem mudar a
 *    assinatura. O chamador decide o que fazer com null (ex.: manter fee do pedido).
 *
 * Timeout curto (3s), fail-safe: qualquer erro vira null (nunca derruba o pedido).
 * A origem resolvida deve ser gravada em orders.delivery_geocode_source ("GOOGLE").
 */
@Service
class GeocodingService(
    @Value("\${google.routes.api-key:}") private val apiKey: String,
    @Value("\${google.geocoding.base-url:https://maps.googleapis.com}") baseUrl: String,
    builder: RestClient.Builder,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val client: RestClient = builder
        .baseUrl(baseUrl)
        .requestFactory(
            org.springframework.http.client.SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(3000)
                setReadTimeout(3000)
            },
        )
        .build()

    /**
     * Resolve o endereco em coordenada. Retorna null se sem chave, sem match, ou erro.
     * A [source] resultante (para delivery_geocode_source) e "GOOGLE" quando resolve.
     */
    fun geocode(street: String?, neighborhood: String?, city: String?, zip: String?): LatLng? {
        if (apiKey.isBlank()) {
            log.debug("Geocoding sem GOOGLE_ROUTES_API_KEY -- retornando null (fallback)")
            return null
        }
        val addressText = listOfNotNull(
            street?.takeIf { it.isNotBlank() },
            neighborhood?.takeIf { it.isNotBlank() },
            city?.takeIf { it.isNotBlank() },
            zip?.takeIf { it.isNotBlank() },
        ).joinToString(", ")
        if (addressText.isBlank()) return null

        return try {
            val uri = UriComponentsBuilder.fromPath("/maps/api/geocode/json")
                .queryParam("address", addressText)
                .queryParam("key", apiKey)
                .build().toUriString()

            @Suppress("UNCHECKED_CAST")
            val body = client.get()
                .uri(uri)
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
            log.warn("Geocoding falhou para '{}': {}", addressText, e.message)
            null
        }
    }
}
