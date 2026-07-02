package com.menuflow.dispatch

import java.util.UUID

/**
 * Eventos internos do despacho por grupo (Spring ApplicationEvent). Carregam
 * SOMENTE identificadores + tenantSlug (nada de PII, nada de entidade JPA): os
 * consumidores (Fase B2 -- envio do poll no grupo, DM ao vencedor) rodam possivel-
 * mente apos o commit e sem sessao do banco, e precisam do slug para rotear de volta
 * ao banco correto no modelo db-per-tenant. O endereco do cliente NUNCA trafega
 * aqui: o consumidor recarrega o pedido no banco do tenant e so revela o endereco
 * em DM ao motoboy vencedor (LGPD).
 */
data class RideOfferedEvent(
    val tenantSlug: String,
    val offerId: UUID,
    val orderId: UUID,
)

data class RideAssignedEvent(
    val tenantSlug: String,
    val offerId: UUID,
    val orderId: UUID,
    val driverId: UUID,
)
