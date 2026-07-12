package com.menuflow.dispatch

import com.menuflow.delivery.HaversineUtil
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
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
 * Tres provedores (escolhidos por [TenantConfig.distanceProvider]):
 *  - [OsrmDistanceProvider]: primario quando OSRM_BASE_URL esta definido e o tenant
 *    usa "OSRM". Self-hosted na A1, custo zero, sem limite de chamadas.
 *  - [GoogleRoutesDistanceProvider]: primario quando GOOGLE_ROUTES_API_KEY esta
 *    definido e o tenant usa "GOOGLE". Rota real de MOTO (TWO_WHEELER), 10k/mes
 *    gratis.
 *  - [HaversineDistanceProvider]: fallback SEM infra externa (linha reta x 1.3).
 *
 * CADEIA DE FALLBACK por valor de [provider]:
 *   "OSRM"     -> OsrmProvider (se configurado) -> googleOrHaversine()
 *   "GOOGLE"   -> GoogleProvider (se configurado) -> Haversine
 *   qualquer   -> Haversine
 *
 * FAIL-OPEN: qualquer falha do OSRM ou Google (timeout, 5xx, corpo inesperado)
 * cai silenciosamente no proximo nivel -- o despacho nunca trava por
 * indisponibilidade de um servico de rotas.
 *
 * Cache em memoria (a distancia restaurante->casa nao muda): chave
 * "lat1,lng1->lat2,lng2", TTL 24h, teto de 10.000 entradas (descarte simples
 * da entrada mais antiga ao estourar).
 */
@Service
class DistanceService(
    private val haversine: HaversineDistanceProvider,
    private val google: GoogleRoutesDistanceProvider,
    private val osrm: OsrmDistanceProvider,
    // A chave do Google agora e resolvida POR REQUISICAO (banco de controle > env >
    // vazio) via GoogleApiKeyProvider, em vez de capturada no boot. Com o banco vazio,
    // resolve() devolve a env atual — comportamento identico ao anterior.
    private val googleApiKeyProvider: GoogleApiKeyProvider,
    @Value("\${osrm.base-url:}") private val osrmBaseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class CacheEntry(val meters: Long, val at: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val ttlMillis = Duration.ofHours(24).toMillis()
    private val maxEntries = 10_000

    /**
     * Distancia rodoviaria em metros. Seleciona o provider com base em [provider]
     * (valor de [TenantConfig.distanceProvider]) seguindo a cadeia OSRM->Google->
     * Haversine. Resultado memoizado por par de coordenadas.
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

        val meters = when (provider.uppercase()) {
            "OSRM" -> {
                if (osrmBaseUrl.isNotBlank()) {
                    runCatching { osrm.distanceMeters(originLat, originLng, destLat, destLng) }
                        .getOrElse {
                            log.warn("OSRM falhou ({}); caindo em Google/Haversine", it.message)
                            googleOrHaversine(originLat, originLng, destLat, destLng)
                        }
                } else {
                    log.debug("OSRM solicitado mas OSRM_BASE_URL nao configurado; usando Google/Haversine")
                    googleOrHaversine(originLat, originLng, destLat, destLng)
                }
            }
            "GOOGLE" -> {
                val googleApiKey = googleApiKeyProvider.resolve()
                if (googleApiKey.isNotBlank()) {
                    runCatching { google.distanceMeters(originLat, originLng, destLat, destLng, googleApiKey) }
                        .getOrElse {
                            log.warn("Google Routes falhou ({}); usando Haversine", it.message)
                            haversine.distanceMeters(originLat, originLng, destLat, destLng)
                        }
                } else {
                    log.debug("GOOGLE solicitado mas nenhuma chave Google resolvida (banco/env); usando Haversine")
                    haversine.distanceMeters(originLat, originLng, destLat, destLng)
                }
            }
            else -> haversine.distanceMeters(originLat, originLng, destLat, destLng)
        }

        putCache(key, meters)
        return meters
    }

    /**
     * Subcadeia Google->Haversine: usada como fallback do OSRM quando este falha ou
     * nao esta configurado.
     */
    private fun googleOrHaversine(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
    ): Long {
        val googleApiKey = googleApiKeyProvider.resolve()
        return if (googleApiKey.isNotBlank()) {
            runCatching { google.distanceMeters(originLat, originLng, destLat, destLng, googleApiKey) }
                .getOrElse {
                    log.warn("Google Routes falhou ({}); usando Haversine", it.message)
                    haversine.distanceMeters(originLat, originLng, destLat, destLng)
                }
        } else {
            haversine.distanceMeters(originLat, originLng, destLat, destLng)
        }
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
 * [DistanceService] cair no Haversine.
 *
 * A chave NAO e mais capturada no boot: o [DistanceService] resolve via
 * [GoogleApiKeyProvider] (banco de controle > env) e passa em [apiKey] a cada chamada.
 * Vai no header X-Goog-Api-Key e NUNCA e logada. O RestClient/infra continua
 * construido no boot — so a chave passa a ser por requisicao.
 */
@Service
class GoogleRoutesDistanceProvider(
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

    fun distanceMeters(originLat: Double, originLng: Double, destLat: Double, destLng: Double, apiKey: String): Long {
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
            .body(object : ParameterizedTypeReference<List<Map<String, Any?>>>() {})
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

/**
 * Provedor self-hosted: OSRM Route v1 rodando na A1 (Oracle Ampere, custo zero,
 * sem limite de chamadas por mes).
 *
 * URL base configurada via env [OSRM_BASE_URL] (ex: http://host.docker.internal:5000
 * ou IP fixo do gateway da A1). Timeout 3s igual ao Google para nao prender thread
 * do despacho; qualquer falha propaga e o [DistanceService] cai na subcadeia
 * Google->Haversine.
 *
 * ATENCAO: a API do OSRM usa LONGITUDE,LATITUDE (ao contrario do Google que usa
 * lat,lng). Isso esta correto na construcao da URI abaixo.
 */
@Service
class OsrmDistanceProvider(
    @Value("\${osrm.base-url:}") baseUrl: String,
    builder: RestClient.Builder,
) {
    private val client: RestClient = builder
        .baseUrl(baseUrl.ifBlank { "http://localhost:5000" })
        .requestFactory(
            org.springframework.http.client.SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(3000)
                setReadTimeout(3000)
            },
        )
        .build()

    /**
     * Calcula a distancia rodoviaria em metros via OSRM Route v1.
     *
     * GET /route/v1/driving/{originLng},{originLat};{destLng},{destLat}?overview=false
     *
     * @throws IllegalStateException se a resposta for invalida ou vazia (capturado
     *   pelo [DistanceService] que cai no proximo provider da cadeia).
     */
    fun distanceMeters(originLat: Double, originLng: Double, destLat: Double, destLng: Double): Long {
        // OSRM: longitude primeiro, latitude segundo (diferente do Google).
        val coords = "$originLng,$originLat;$destLng,$destLat"

        val response = client.get()
            .uri { builder ->
                builder
                    .path("/route/v1/driving/$coords")
                    .queryParam("overview", "false")
                    .build()
            }
            .retrieve()
            .onStatus(HttpStatusCode::isError) { _, res ->
                throw IllegalStateException("OSRM HTTP ${res.statusCode}")
            }
            .body(object : ParameterizedTypeReference<Map<String, Any?>>() {})
            ?: throw IllegalStateException("OSRM: corpo vazio")

        @Suppress("UNCHECKED_CAST")
        val routes = response["routes"] as? List<Map<String, Any?>>
            ?: throw IllegalStateException("OSRM: campo routes ausente ou tipo inesperado")
        val first = routes.firstOrNull()
            ?: throw IllegalStateException("OSRM: routes vazio (sem rota encontrada)")
        return (first["distance"] as? Number)?.let { Math.round(it.toDouble()) }
            ?: throw IllegalStateException("OSRM: campo distance ausente")
    }
}

/**
 * Ordem otima de UMA rota com N paradas (issue #4). O primeiro ponto e SEMPRE a
 * origem fixa (o restaurante); os demais sao os pontos de entrega. Resolve o
 * "problema do caixeiro viajante" aproximado via servico OSRM /trip — o MESMO
 * `osrm-routed` que ja serve /route (nenhuma flag/binario especial).
 *
 * GET /trip/v1/driving/{lon,lat;lon,lat;...}?source=first&roundtrip=false
 *   - source=first  : fixa a origem (restaurante) como inicio da rota;
 *   - roundtrip=false: o motoboy NAO volta ao restaurante ao fim.
 *
 * ATENCAO: como todo o OSRM, a ordem e LONGITUDE,LATITUDE (nao lat,lng).
 *
 * A resposta traz `waypoints[i].waypoint_index` = posicao do i-esimo ponto de
 * ENTRADA na rota otimizada; `trips[0].distance/duration` sao os totais. Qualquer
 * falha (timeout, 5xx, code != Ok, corpo inesperado) propaga como
 * IllegalStateException — o [RouteOptimizationService] captura e cai no fallback
 * deterministico (FAIL-OPEN: a roteirizacao nunca trava a operacao).
 */
@Service
class OsrmTripProvider(
    @Value("\${osrm.base-url:}") baseUrl: String,
    builder: RestClient.Builder,
) {
    // Sem requestFactory custom: o provider usa o builder injetado como veio, para que
    // o teste consiga bindar um MockRestServiceServer e assertar a URI EFETIVA emitida
    // (garantia contra o bug do `?` percent-encodado). Timeout/hang do OSRM e coberto
    // FAIL-OPEN pelo RouteOptimizationService (qualquer excecao -> fallback Haversine);
    // e um endpoint de planejamento, baixo volume/admin.
    private val client: RestClient = builder
        .baseUrl(baseUrl.ifBlank { "http://localhost:5000" })
        .build()

    /**
     * Otimiza a ordem de visita. [points] sao os pontos APOS a origem (as entregas),
     * na ordem em que foram passados; a origem [originLat]/[originLng] entra como
     * primeiro waypoint fixo. Devolve os indices de [points] (0-based) na ordem otima
     * de visita, mais os totais da rota.
     *
     * @return [TripResult] com a permutacao dos indices de entrada e os totais.
     * @throws IllegalStateException em qualquer resposta invalida (capturado pelo servico).
     */
    fun optimize(originLat: Double, originLng: Double, points: List<Pair<Double, Double>>): TripResult {
        require(points.isNotEmpty()) { "OSRM trip: sem pontos de entrega" }
        val coordsPath = tripCoordsPath(originLat, originLng, points)

        val response = client.get()
            .uri { b ->
                // source/roundtrip vao como queryParam SEPARADO do path. Se fossem
                // concatenados no .path(), o DefaultUriBuilder percent-encodaria o `?`
                // (-> %3F) e o OSRM /trip erraria SEMPRE, matando a otimizacao real.
                b.path(coordsPath)
                    .queryParam("source", "first")
                    .queryParam("roundtrip", "false")
                    .build()
            }
            .retrieve()
            .onStatus(HttpStatusCode::isError) { _, res ->
                throw IllegalStateException("OSRM trip HTTP ${res.statusCode}")
            }
            .body(object : ParameterizedTypeReference<Map<String, Any?>>() {})
            ?: throw IllegalStateException("OSRM trip: corpo vazio")

        return parseTrip(response, points.size)
    }

    companion object {
        /**
         * Monta SO o path de coordenadas do /trip (sem query string): origem
         * (restaurante) como primeiro waypoint fixo, seguido das entregas, TODOS em
         * LONGITUDE,LATITUDE (padrao OSRM). Os parametros source=first/roundtrip=false
         * sao adicionados via queryParam no [optimize] — NUNCA concatenados aqui (o
         * DefaultUriBuilder percent-encodaria o `?`). Puro para provar a ordem LON,LAT.
         */
        fun tripCoordsPath(originLat: Double, originLng: Double, points: List<Pair<Double, Double>>): String {
            val all = buildList {
                add(originLng to originLat)
                points.forEach { (lat, lng) -> add(lng to lat) }
            }
            val coords = all.joinToString(";") { (lng, lat) -> "$lng,$lat" }
            return "/trip/v1/driving/$coords"
        }

        /**
         * Interpreta a resposta do /trip. [pointCount] e o numero de ENTREGAS (sem a
         * origem). Devolve a permutacao dos indices de entrada (0-based) na ordem otima
         * + os totais. Puro para teste isolado com corpo canonico do OSRM.
         *
         * @throws IllegalStateException em qualquer corpo invalido (code != Ok, tamanhos
         *   inconsistentes, campos ausentes) — o servico captura e cai no fallback.
         */
        fun parseTrip(response: Map<String, Any?>, pointCount: Int): TripResult {
            val code = response["code"] as? String
            if (code != "Ok") {
                throw IllegalStateException("OSRM trip: code=$code (rota nao encontrada)")
            }

            @Suppress("UNCHECKED_CAST")
            val waypoints = response["waypoints"] as? List<Map<String, Any?>>
                ?: throw IllegalStateException("OSRM trip: waypoints ausente ou tipo inesperado")
            if (waypoints.size != pointCount + 1) {
                throw IllegalStateException("OSRM trip: waypoints (${waypoints.size}) != pontos (${pointCount + 1})")
            }

            @Suppress("UNCHECKED_CAST")
            val trips = response["trips"] as? List<Map<String, Any?>>
                ?: throw IllegalStateException("OSRM trip: trips ausente ou tipo inesperado")
            val trip = trips.firstOrNull()
                ?: throw IllegalStateException("OSRM trip: trips vazio")
            val totalMeters = (trip["distance"] as? Number)?.let { Math.round(it.toDouble()) }
                ?: throw IllegalStateException("OSRM trip: distance ausente")
            val totalSeconds = (trip["duration"] as? Number)?.let { Math.round(it.toDouble()) }
                ?: throw IllegalStateException("OSRM trip: duration ausente")

            // waypoints[0] e a origem (waypoint_index 0 por source=first). As entregas
            // sao waypoints[1..N]; o waypoint_index de cada uma da a posicao na rota
            // otimizada. Ordena os indices de ENTRADA das entregas por waypoint_index.
            val deliveryWaypoints = waypoints.drop(1).mapIndexed { inputIdx, wp ->
                val wpIndex = (wp["waypoint_index"] as? Number)?.toInt()
                    ?: throw IllegalStateException("OSRM trip: waypoint_index ausente")
                inputIdx to wpIndex
            }
            val orderedInputIndices = deliveryWaypoints.sortedBy { it.second }.map { it.first }

            return TripResult(
                orderedInputIndices = orderedInputIndices,
                totalDistanceMeters = totalMeters,
                totalDurationSeconds = totalSeconds,
            )
        }
    }
}

/**
 * Resultado da otimizacao pelo OSRM /trip. [orderedInputIndices] e a permutacao dos
 * indices de ENTRADA (0-based, referente a lista `points` passada ao provider) na
 * ordem otima de visita. Totais rodoviarios em metros/segundos.
 */
data class TripResult(
    val orderedInputIndices: List<Int>,
    val totalDistanceMeters: Long,
    val totalDurationSeconds: Long,
)
