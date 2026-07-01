package com.menuflow.opendelivery

import com.menuflow.channels.ChannelOrderAdapter
import com.menuflow.model.Order
import org.slf4j.LoggerFactory

/**
 * Base dos adapters Open Delivery (99Food / Rappi) — Fase 5.5a, STUB.
 *
 * Implementa o contrato real [ChannelOrderAdapter] (que opera sobre [Order] e se
 * registra no [com.menuflow.channels.ChannelOrderService] pela propriedade
 * [channelType]). Por enquanto so registra em log a intencao de propagar o evento;
 * a chamada HTTP real (POST /orders/{id}/confirm|dispatch|cancel do protocolo Open
 * Delivery) entra na Fase 5.5b, junto do cliente HTTP e do OAuth2 client_credentials.
 *
 * Cada plataforma concreta informa seu [platform] (nome) e sua [baseUrl].
 */
abstract class OpenDeliveryChannelAdapter : ChannelOrderAdapter {
    protected val log = LoggerFactory.getLogger(javaClass)

    /** Nome da plataforma (ex.: NINETY_NINE, RAPPI) — usado so em log nesta fase. */
    abstract fun platform(): String

    /** URL base da API Open Delivery da plataforma — usada na 5.5b. */
    abstract fun baseUrl(): String

    override fun confirmOrder(order: Order) {
        log.info("[OpenDelivery:{}] confirmOrder stub — orderId={}", platform(), order.id)
        // 5.5b: POST {baseUrl}/orders/{externalId}/confirm
    }

    override fun dispatchOrder(order: Order) {
        log.info("[OpenDelivery:{}] dispatchOrder stub — orderId={}", platform(), order.id)
        // 5.5b: POST {baseUrl}/orders/{externalId}/dispatch
    }

    override fun cancelOrder(order: Order, reason: String) {
        log.info("[OpenDelivery:{}] cancelOrder stub — orderId={} reason={}", platform(), order.id, reason)
        // 5.5b: POST {baseUrl}/orders/{externalId}/cancel
    }
}
