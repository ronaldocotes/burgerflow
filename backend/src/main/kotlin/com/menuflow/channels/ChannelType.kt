package com.menuflow.channels

/**
 * Plataforma de ORIGEM de um pedido (Fase 5.0 — walking skeleton multicanal).
 *
 * Não confundir com [com.menuflow.model.SalesChannel] (COUNTER/DINE_IN/DELIVERY/
 * ONLINE), que é o recorte interno de venda usado no DRE. ChannelType é a
 * plataforma externa de onde o pedido entrou: canal próprio, iFood ou Rappi.
 *
 * Persistido em orders.external_origin (VARCHAR) pelo NOME do enum.
 */
enum class ChannelType(val label: String) {
    OWN("Canal Próprio"),
    IFOOD("iFood"),
    RAPPI("Rappi"),
}
