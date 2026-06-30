package com.menuflow.channels

import com.menuflow.model.Order
import org.springframework.stereotype.Service

/**
 * Registry de adapters de canal (Fase 5.0). O Spring injeta todos os
 * [ChannelOrderAdapter] do contexto; o service despacha cada operação para o
 * adapter da plataforma de origem do pedido ([Order.channelType]).
 *
 * Fallback seguro: origem desconhecida cai no [ChannelType.OWN] (no-op) em vez de
 * quebrar — um pedido com external_origin inesperado nunca derruba o fluxo.
 */
@Service
class ChannelOrderService(adapters: List<ChannelOrderAdapter>) {
    private val registry = adapters.associateBy { it.channelType }

    fun adapter(type: ChannelType): ChannelOrderAdapter =
        registry[type] ?: registry.getValue(ChannelType.OWN)

    fun confirmOrder(order: Order) = adapter(order.channelType()).confirmOrder(order)

    fun dispatchOrder(order: Order) = adapter(order.channelType()).dispatchOrder(order)

    fun cancelOrder(order: Order, reason: String) =
        adapter(order.channelType()).cancelOrder(order, reason)
}
