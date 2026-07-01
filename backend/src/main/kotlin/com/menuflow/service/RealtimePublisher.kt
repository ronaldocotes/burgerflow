package com.menuflow.service

import com.menuflow.dto.KdsOrderEvent
import com.menuflow.model.Order
import org.slf4j.LoggerFactory
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * Publishes live events to STOMP topics for the KDS and delivery screens.
 *
 * The destination tenant slug is supplied by the caller from the AUTHENTICATED
 * principal / signed JWT (never a client header), so events are only ever pushed
 * to the publisher's own tenant topic — no cross-tenant fan-out.
 */
@Component
class RealtimePublisher(
    private val messagingTemplate: SimpMessagingTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Broadcast an order status change to /topic/kds/{tenantSlug}. */
    fun publishKds(tenantSlug: String, order: Order) {
        val event = KdsOrderEvent.from(order)
        val destination = "/topic/kds/$tenantSlug"
        messagingTemplate.convertAndSend(destination, event)
        log.debug("KDS event -> {} order={} status={}", destination, order.orderNumber, order.status)
    }

    /** Broadcast a delivery status change to /topic/delivery/{tenantSlug}. */
    fun publishDelivery(tenantSlug: String, payload: Any) {
        val destination = "/topic/delivery/$tenantSlug"
        messagingTemplate.convertAndSend(destination, payload)
        log.debug("Delivery event -> {}", destination)
    }

    /** Broadcast a table/comanda state change to /topic/tables/{tenantSlug}. */
    fun publishTables(tenantSlug: String, payload: Any) {
        val destination = "/topic/tables/$tenantSlug"
        messagingTemplate.convertAndSend(destination, payload)
        log.debug("Table event -> {}", destination)
    }

    /**
     * Publica uma oferta de entrega (Fase 6.1) no canal de ofertas do tenant
     * (/topic/delivery/{tenantSlug}/offers). O payload carrega o driverId alvo; o app
     * do motoboy assina o canal e filtra pela propria oferta. Usamos um canal por
     * TENANT (nao por driver) para manter a allowlist de SUBSCRIBE por correspondencia
     * exata e nao criar nova superficie de IDOR no WebSocket (o driverId nao esta no
     * JWT). O aceite/recusa e sempre validado por dono no servidor.
     */
    fun publishDeliveryOffer(tenantSlug: String, driverId: java.util.UUID, payload: Any) {
        val destination = "/topic/delivery/$tenantSlug/offers"
        messagingTemplate.convertAndSend(destination, payload)
        log.debug("Delivery offer -> {} driver={}", destination, driverId)
    }

    /** Publica a posicao ao vivo de um entregador em /topic/delivery/{tenantSlug}. */
    fun publishDriverLocation(tenantSlug: String, payload: Any) {
        val destination = "/topic/delivery/$tenantSlug"
        messagingTemplate.convertAndSend(destination, payload)
        log.debug("Driver location -> {}", destination)
    }
}
