package com.menuflow.dispatch

import com.menuflow.delivery.HaversineUtil
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Distancia RODOVIARIA (metros) entre a origem (restaurante) e o destino (casa do
 * cliente), usada para precificar a entrega e o payout do motoboy pela distancia
 * real percorrida -- nao pela linha reta.
 *
 * Dois provedores:
 *  - [GoogleRoutesDistanceProvider]: primario quando GOOGLE_ROUTES_API_KEY esta
 *    definido. Rota real de MOTO (TWO_WHEELER), 10k chamadas gratis/mes.
 *  - [HaversineDistanceProvider]: fallback SEM infra externa (linha reta x 1.3).
 *
 * FAIL-OPEN: qualquer falha do Google (timeout, quota, 5xx, corpo inesperado) cai
 * silenciosamente no Haversine -- o despacho nunca trava por indisponibilidade de
 * um servico de rotas. Cache em memoria (a distancia restaurante->casa nao muda):
 * chave "lat1,lng1->lat2,lng2", TTL 24h, teto de 10.000 entradas (descarte simples
 * da entrada mais antiga ao estourar).
 */
@Service
class DistanceService(
    private val haversine: HaversineDistanceProvider,
    private val google: GoogleRoutesDistanceProvider,
    @Value("\${google.routes.api-key:}") private val googleApiKey: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class CacheEntry(val meters: Long, val at: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val ttlMillis = Duration.ofHours(24).toMillis()
    private val maxEntries = 10_000

    /**
     * Distancia rodoviaria em metros. Usa o Google quando ha chave configurada (e
     * o provider nao forcado a HAVERSINE); em qualquer falha, Haversine. Resultado
     * memoizado por par de coordenadas.
     */
    fun getRoadDistanceMeters(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
        provider: String = "HAVERSINE",
    ): Long {
        val key = "$originLat,$originLng->$destLat,$destLng"
        cache[key]?.let { entry ->
            if (System.currentTimeMillis() - entry.at < ttlMillis) return entry.meters
            cache.remove(key)
        }

        val useGoogle = googleApiKey.isNotBlank() && provider.uppercase() != "HAVERSINE"
        val meters = if (useGoogle) {
            runCatching { google.distanceMeters(originLat, originLng, destLat, destLng) }
                .getOrElse {
                    log.warn("Google Routes falhou ({}); usando Haversine", it.message)
                    haversine.distanceMeters(originLat, originLng, destLat, destLng)
                }
        } else {
            haversine.distanceMeters(originLat, originLng, destLat, destLng)
        }

        putCache(key, meters)
        return meters
    }

    private fun putCache(key: String, meters: Long) {
        if (cache.size >= maxEntries) {
            // LRU simples: remove a entrada mais antiga por timestamp.
            cache.entries.minByOrNull { it.value.at }?.let { cache.remove(it.key) }
        }
        cache[key] = CacheEntry(meters, System.currentTimeMillis())
    }
}

/** Provedor de fallback: Haversine x 1.3 (fator urbano), sem servico externo. */
@Service
class HaversineDistanceProvider {
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Long =
        (HaversineUtil.estimatedRoadKm(lat1, lng1, lat2, lng2) * 1000.0).toLong()
}

/**
 * Provedor primario: Google Routes (Compute Route Matrix), modo TWO_WHEELER.
 * Timeout curto (3s) para nao prender thread do despacho; erros propagam para o
 * [DistanceService] cair no Haversine. A chave vai no header X-Goog-Api-Key e NUNCA
 * e logada.
 */
@Service
class GoogleRoutesDistanceProvider(
    @Value("\${google.routes.api-key:}") private val apiKey: String,
    @Value("\${google.routes.base-url:https://routes.googleapis.com}") baseUrl: String,
    builder: RestClient.Builder,
) {
    private val client: RestClient = builder
        .baseUrl(baseUrl)
        .requestFactory(
            org.springframework.http.client.SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(3000)
                setReadTimeout(3000)
            },
        )
        .build()

    fun distanceMeters(originLat: Double, originLng: Double, destLat: Double, destLng: Double): Long {
        val body = mapOf(
            "origins" to listOf(waypoint(originLat, originLng)),
            "destinations" to listOf(waypoint(destLat, destLng)),
            "travelMode" to "TWO_WHEELER",
        )
        val response = client.post()
            .uri("/distanceMatrix/v2:computeRouteMatrix")
            .header("X-Goog-Api-Key", apiKey)
            .header(
                "X-Goog-FieldMask",
                "originIndex,destinationIndex,duration,distanceMeters,status",
            )
            .header("Content-Type", "application/json")
            .body(body)
            .retrieve()
            .onStatus(HttpStatusCode::isError) { _, res ->
                throw IllegalStateException("Google Routes HTTP ${res.statusCode}")
            }
            .body(object : org.springframework.core.ParameterizedTypeReference<List<Map<String, Any?>>>() {})
            ?: throw IllegalStateException("Google Routes: corpo vazio")

        val element = response.firstOrNull()
            ?: throw IllegalStateException("Google Routes: matriz vazia")
        val meters = (element["distanceMeters"] as? Number)?.toLong()
            ?: throw IllegalStateException("Google Routes: sem distanceMeters")
        return meters
    }

    private fun waypoint(lat: Double, lng: Double): Map<String, Any> =
        mapOf("waypoint" to mapOf("location" to mapOf("latLng" to mapOf("latitude" to lat, "longitude" to lng))))
}
