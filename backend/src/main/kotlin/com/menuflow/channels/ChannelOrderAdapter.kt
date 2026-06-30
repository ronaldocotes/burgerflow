package com.menuflow.channels

import com.menuflow.model.Order

/**
 * Contrato de integração com um canal de venda (Fase 5.0).
 *
 * Cada plataforma (próprio, iFood, Rappi) implementa como propagar mudanças de
 * estado do pedido para fora. Métodos têm corpo vazio por padrão: um adapter só
 * sobrescreve o que faz sentido para a sua plataforma (o canal próprio não
 * propaga nada — o pedido já vive aqui).
 */
interface ChannelOrderAdapter {
    val channelType: ChannelType

    /** Confirma/aceita o pedido na plataforma de origem. */
    fun confirmOrder(order: Order) {}

    /** Marca o pedido como despachado/saiu para entrega na plataforma. */
    fun dispatchOrder(order: Order) {}

    /** Cancela o pedido na plataforma de origem, com motivo. */
    fun cancelOrder(order: Order, reason: String) {}

    /** Propaga uma mudança genérica de status para a plataforma. */
    fun updateStatus(order: Order) {}
}
