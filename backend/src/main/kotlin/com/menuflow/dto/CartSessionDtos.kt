package com.menuflow.dto

import com.menuflow.model.CartSession
import com.menuflow.model.CartSessionStatus
import java.time.Instant
import java.util.UUID

/**
 * Comanda de recuperacao de carrinho abandonado (Fase 3.5) para o painel do admin.
 * Exposta em GET /cart-sessions. Sem campos sensiveis de margem — apenas o necessario
 * para o operador acompanhar o funil (ativas / enviadas / recuperadas / expiradas).
 */
data class CartSessionResponse(
    val id: UUID,
    val orderId: UUID,
    val customerPhone: String?,
    val totalCents: Long,
    val status: CartSessionStatus,
    val recoveryMessageSentAt: Instant?,
    val recoveredAt: Instant?,
    val expiredAt: Instant?,
    val createdAt: Instant,
) {
    companion object {
        fun from(c: CartSession) =
            CartSessionResponse(
                id = c.id!!,
                orderId = c.orderId,
                customerPhone = c.customerPhone,
                totalCents = c.totalCents,
                status = c.status,
                recoveryMessageSentAt = c.recoveryMessageSentAt,
                recoveredAt = c.recoveredAt,
                expiredAt = c.expiredAt,
                createdAt = c.createdAt,
            )
    }
}
