package com.menuflow.channels

import com.menuflow.model.Order
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Stub do canal iFood (Fase 5.0). Ainda SEM integração real: por enquanto só
 * registra em log a intenção de propagar o evento. A chamada HTTP à API do iFood
 * (confirmar/despachar/cancelar) entra numa fase posterior.
 */
@Component
class IFoodChannelAdapter : ChannelOrderAdapter {
    private val log = LoggerFactory.getLogger(javaClass)
    override val channelType = ChannelType.IFOOD

    override fun confirmOrder(order: Order) {
        log.info("[iFood stub] confirmOrder orderId={}", order.id)
    }

    override fun dispatchOrder(order: Order) {
        log.info("[iFood stub] dispatchOrder orderId={}", order.id)
    }

    override fun cancelOrder(order: Order, reason: String) {
        log.info("[iFood stub] cancelOrder orderId={} reason={}", order.id, reason)
    }
}
