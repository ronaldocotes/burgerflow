package com.menuflow.repository.tenant

import com.menuflow.model.PaymentIntent
import com.menuflow.model.PaymentIntentStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface PaymentIntentRepository : JpaRepository<PaymentIntent, UUID> {

    /** Cobrancas de um pedido (mais recente primeiro) — base da idempotencia do create. */
    fun findByOrderIdOrderByCreatedAtDesc(orderId: UUID): List<PaymentIntent>

    /** Resolve a cobranca pelo id do Asaas (chave de chegada do webhook). UNIQUE no banco. */
    fun findByAsaasPaymentId(asaasPaymentId: String): PaymentIntent?

    /** Cobrancas pendentes ja vencidas — alvo da reconciliacao (marcar EXPIRED). */
    fun findAllByStatusAndExpiresAtBefore(
        status: PaymentIntentStatus,
        before: Instant,
    ): List<PaymentIntent>
}
