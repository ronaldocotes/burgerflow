package com.menuflow.event

import java.util.UUID

/**
 * Fato de dominio publicado quando um pedido transita para paymentStatus = PAID.
 *
 * E a "espinha" da Fase 3: pontos de fidelidade, futuras integracoes (NPS, BI) e
 * qualquer reacao pos-pagamento se penduram aqui em vez de acoplar ao fluxo de
 * cobranca. Publicado DENTRO da transacao que marca o pedido como pago (PdvService.
 * pay e PixPaymentService.handleWebhook); os listeners consomem APOS o commit
 * (@TransactionalEventListener AFTER_COMMIT), fora da transacao original.
 *
 * Carrega SOMENTE dados primitivos (nada de entidade JPA): o consumidor roda apos o
 * commit, possivelmente sem a sessao do banco, e precisa do tenantSlug para rotear
 * de volta ao banco correto no modelo db-per-tenant.
 */
data class OrderPaidEvent(
    /** Slug do tenant (banco) onde o pedido vive — necessario para o routing AFTER_COMMIT. */
    val tenantSlug: String,
    val orderId: UUID,
    /** Cliente cadastrado do pedido; null em pedido anonimo (sem fidelidade). */
    val customerId: UUID?,
    val customerPhone: String?,
    val totalCents: Long,
)
