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
}
