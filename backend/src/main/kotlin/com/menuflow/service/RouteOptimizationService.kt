package com.menuflow.service

import com.menuflow.delivery.HaversineUtil
import com.menuflow.dispatch.OsrmTripProvider
import com.menuflow.dto.DeliveryOrderResponse
import com.menuflow.dto.RouteAssignRequest
import com.menuflow.dto.RouteOptimizeRequest
import com.menuflow.dto.RouteOptimizeResponse
import com.menuflow.dto.RouteStopResponse
import com.menuflow.exception.BusinessException
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.model.DeliveryStatus
import com.menuflow.model.Order
import com.menuflow.model.OrderStatus
import com.menuflow.model.OrderType
import com.menuflow.repository.tenant.DeliveryDriverRepository
import com.menuflow.repository.tenant.OrderRepository
import com.menuflow.repository.tenant.TenantConfigRepository
import com.menuflow.security.SecurityUtils
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Roteirizacao de multiplas entregas (issue #4). Um motoboy da FROTA sai com N
 * pedidos; este servico calcula a ORDEM otima de visita (F1, stateless) e persiste a
 * sequencia atribuindo os pedidos ao motoboy (F2), para o app do motoboy ler a rota.
 *
 * O ponto de partida da rota e SEMPRE o restaurante (tenant_config.restaurant_lat/
 * lng). A otimizacao usa o OSRM /trip self-hosted (mesmo binario do /route); qualquer
 * falha cai num fallback deterministico (Haversine crescente a partir do restaurante)
 * — FAIL-OPEN: a roteirizacao nunca trava a operacao por indisponibilidade do OSRM.
 *
 * Tudo vive no banco do TENANT (db-per-tenant): findAllById ja e escopado ao
 * restaurante pela datasource roteada; id de outro tenant simplesmente nao existe
 * aqui (404, sem vazamento).
 */
@Service
class RouteOptimizationService(
    private val orderRepository: OrderRepository,
    private val tenantConfigRepository: TenantConfigRepository,
    private val driverRepository: DeliveryDriverRepository,
    private val osrmTripProvider: OsrmTripProvider,
    private val realtimePublisher: RealtimePublisher,
    @Value("\${osrm.base-url:}") private val osrmBaseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /**
         * Teto de paradas por rota. O /trip do OSRM resolve o TSP por forca bruta
         * (permutacoes) ate ~10-12 pontos e por heuristica acima disso, degradando a
         * latencia; 25 e um teto conservador para o cenario de hamburgueria SMB (um
         * motoboy raramente sai com mais que ~8 entregas).
         */
        const val MAX_STOPS = 25

        /**
         * Fallback deterministico: ordena as entregas pela distancia em LINHA RETA
         * (Haversine) ao restaurante, crescente. Puro (sem I/O) — testavel isolado e
         * estavel para o mesmo conjunto de entrada (desempate por id p/ nao depender da
         * ordem de chegada quando duas entregas equidistam).
         */
        fun fallbackOrder(
            originLat: Double,
            originLng: Double,
            points: List<Triple<UUID, Double, Double>>,
        ): List<UUID> =
            points.sortedWith(
                compareBy(
                    { HaversineUtil.distanceKm(originLat, originLng, it.second, it.third) },
                    { it.first },
                ),
            ).map { it.first }
    }

    /**
     * F1 — ordem otima STATELESS. Nao grava nada. Valida: pedidos do tenant, de
     * ENTREGA, nao terminais e COM coordenadas; restaurante com coordenadas; bound de
     * paradas. Devolve as paradas na ordem de visita + totais + optimized.
     */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun optimize(req: RouteOptimizeRequest): RouteOptimizeResponse {
        val orders = loadOrdered(req.orderIds, requireCoords = true)
        val (originLat, originLng) = restaurantOrigin()
        return buildRoute(originLat, originLng, orders)
    }

    /**
     * F2 — confirma/atribui a rota otimizada a um motoboy da FROTA. [req.orderIds] JA
     * vem na ORDEM da rota (a sequencia devolvida pelo F1). Grava delivery_sequence
     * 1..N nessa ordem, associa os pedidos ao motoboy e carimba deliveryStatus
     * ASSIGNED (sem regredir um despacho ja em andamento). Transacional e idempotente:
     * reenviar a mesma rota converge para o mesmo estado. Publica cada pedido no
     * topico de entrega do tenant (o painel/app veem a rota ao vivo).
     */
    @Transactional("tenantTransactionManager")
    fun assignRoute(req: RouteAssignRequest): List<DeliveryOrderResponse> {
        val driver = driverRepository.findById(req.driverId)
            .orElseThrow { ResourceNotFoundException("Motoboy nao encontrado: ${req.driverId}") }
        if (!driver.active) {
            throw BusinessException("Motoboy ${driver.name} esta inativo")
        }
        // #4 e FROTA-only: freelancer entra por leilao de corrida unica (Fase B2), nao
        // por rota multipla planejada pelo gestor.
        if (driver.driverType != "FROTA") {
            throw BusinessException("Roteirizacao de multiplas entregas e apenas para motoboys da FROTA")
        }

        // Coords nao sao exigidas aqui (a ordem ja foi decidida no F1); exigimos apenas
        // que sejam pedidos de entrega validos e nao terminais.
        val orders = loadOrdered(req.orderIds, requireCoords = false)
        val newIds = orders.mapNotNull { it.id }.toSet()

        // MEDIO-2(a): pedidos que estavam na rota ANTERIOR deste motoboy mas nao estao
        // na nova lista sao desassociados e perdem a sequencia — senao sobraria uma
        // "parada" orfa (sequencia stale) aparecendo no app. Fecha tambem o pedido que
        // some da rota sem voltar para o pool de nao-atribuidos.
        val dropped = orderRepository.findByDriverIdAndDeliverySequenceIsNotNull(driver.id!!)
            .filter { it.id !in newIds }
        dropped.forEach { o ->
            o.deliverySequence = null
            o.driverId = null
            // So devolve ao pool quem ainda estava apenas ASSIGNED; um despacho ja em
            // andamento (OUT_FOR_DELIVERY etc.) nao e regredido por uma re-roteirizacao.
            if (o.deliveryStatus == DeliveryStatus.ASSIGNED) {
                o.deliveryStatus = null
            }
        }

        orders.forEachIndexed { idx, order ->
            order.driverId = driver.id
            order.deliverySequence = idx + 1
            // BAIXO-1: re-despachar um pedido que FALHOU o revive como ASSIGNED. Sem
            // isso ele ficaria roteirizado porem invisivel no app (findActiveOrdersFor
            // Driver exclui FAILED) — parada fantasma. So nao mexemos num despacho que
            // ja avancou (PICKED_UP/OUT_FOR_DELIVERY etc.).
            if (order.deliveryStatus == null || order.deliveryStatus == DeliveryStatus.FAILED) {
                order.deliveryStatus = DeliveryStatus.ASSIGNED
            }
        }
        val saved = orderRepository.saveAll(dropped + orders)
            .filter { it.id in newIds }
            .sortedBy { it.deliverySequence }

        val slug = SecurityUtils.currentPrincipalOrThrow().tenantSlug
        val payloads = saved.map { DeliveryOrderResponse.from(it) }
        payloads.forEach { realtimePublisher.publishDelivery(slug, it) }
        return payloads
    }

    // -------------------------------------------------------------------------

    /**
     * Carrega os pedidos preservando a ORDEM de [ids] (importante no F2, onde a ordem
     * e a sequencia da rota). Valida bound, duplicatas, existencia (multi-tenant: id de
     * outro restaurante nao existe neste banco -> 404), tipo ENTREGA, nao-terminal e,
     * opcionalmente, presenca de coordenadas.
     */
    private fun loadOrdered(ids: List<UUID>, requireCoords: Boolean): List<Order> {
        if (ids.isEmpty()) throw BusinessException("Rota sem pedidos")
        if (ids.size > MAX_STOPS) {
            throw BusinessException("Rota com ${ids.size} pedidos acima do maximo de $MAX_STOPS")
        }
        if (ids.distinct().size != ids.size) {
            throw BusinessException("Ha pedidos repetidos na rota")
        }
        val byId = orderRepository.findAllById(ids).associateBy { it.id!! }
        val missing = ids.filter { it !in byId }
        if (missing.isNotEmpty()) {
            throw ResourceNotFoundException("Pedido(s) nao encontrado(s): ${missing.joinToString()}")
        }
        val ordered = ids.map { byId.getValue(it) }
        ordered.forEach { o ->
            if (o.orderType != OrderType.DELIVERY) {
                throw BusinessException("Pedido ${o.orderNumber} nao e de entrega")
            }
            if (o.status == OrderStatus.CANCELLED || o.status == OrderStatus.DELIVERED) {
                throw BusinessException("Pedido ${o.orderNumber} nao esta disponivel para rota (status ${o.status})")
            }
            if (requireCoords && (o.deliveryLat == null || o.deliveryLng == null)) {
                throw BusinessException("Pedido ${o.orderNumber} nao tem coordenadas de entrega")
            }
        }
        return ordered
    }

    /** Coordenadas do restaurante (origem da rota); 400 se nao configuradas. */
    private fun restaurantOrigin(): Pair<Double, Double> {
        val config = tenantConfigRepository.findFirstByOrderByCreatedAtAsc()
        val lat = config?.restaurantLat
        val lng = config?.restaurantLng
        if (lat == null || lng == null) {
            throw BusinessException("Configure as coordenadas do restaurante para roteirizar entregas")
        }
        return lat to lng
    }

    /**
     * Monta a resposta: tenta OSRM /trip (se configurado) e, em qualquer falha, cai no
     * fallback Haversine. [orders] entram na ordem original do request; a saida vem na
     * ordem de visita.
     */
    private fun buildRoute(originLat: Double, originLng: Double, orders: List<Order>): RouteOptimizeResponse {
        val points = orders.map { it.deliveryLat!! to it.deliveryLng!! }

        val trip = if (osrmBaseUrl.isNotBlank()) {
            runCatching { osrmTripProvider.optimize(originLat, originLng, points) }
                .getOrElse {
                    log.warn("OSRM /trip falhou ({}); usando fallback Haversine para a rota", it.message)
                    null
                }
        } else {
            log.debug("OSRM_BASE_URL nao configurado; rota por fallback Haversine")
            null
        }

        if (trip != null) {
            val orderedOrders = trip.orderedInputIndices.map { orders[it] }
            return RouteOptimizeResponse(
                stops = toStops(orderedOrders),
                totalDistanceMeters = trip.totalDistanceMeters,
                totalDurationSeconds = trip.totalDurationSeconds,
                optimized = true,
            )
        }

        // Fallback deterministico.
        val orderedIds = fallbackOrder(
            originLat, originLng,
            orders.map { Triple(it.id!!, it.deliveryLat!!, it.deliveryLng!!) },
        )
        val orderedOrders = orderedIds.map { id -> orders.first { it.id == id } }
        return RouteOptimizeResponse(
            stops = toStops(orderedOrders),
            totalDistanceMeters = chainRoadMeters(originLat, originLng, orderedOrders),
            totalDurationSeconds = null,
            optimized = false,
        )
    }

    private fun toStops(ordered: List<Order>): List<RouteStopResponse> =
        ordered.mapIndexed { idx, o ->
            RouteStopResponse(
                orderId = o.id!!,
                position = idx + 1,
                orderNumber = o.orderNumber,
                deliveryLat = o.deliveryLat!!,
                deliveryLng = o.deliveryLng!!,
                deliveryRecipientName = o.deliveryRecipientName,
                deliveryNeighborhood = o.deliveryNeighborhood,
                deliveryStreet = o.deliveryStreet,
                deliveryNumber = o.deliveryNumber,
            )
        }

    /** Distancia rodoviaria ESTIMADA (metros) do encadeamento restaurante->p1->...->pN. */
    private fun chainRoadMeters(originLat: Double, originLng: Double, ordered: List<Order>): Long {
        var meters = 0.0
        var lat = originLat
        var lng = originLng
        for (o in ordered) {
            meters += HaversineUtil.estimatedRoadKm(lat, lng, o.deliveryLat!!, o.deliveryLng!!) * 1000.0
            lat = o.deliveryLat!!
            lng = o.deliveryLng!!
        }
        return Math.round(meters)
    }
}
