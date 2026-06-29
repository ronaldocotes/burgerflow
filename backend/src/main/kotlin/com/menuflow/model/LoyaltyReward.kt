package com.menuflow.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Recompensa desbloqueada (punch ganho) de um cliente (Fase 3.3). Vive no banco do
 * TENANT. Criada quando o cliente acumula pontos >= limite do programa. Resgatada
 * (redeemedAt) por um operador autorizado; expires_at NULL = nao expira.
 */
@Entity
@Table(name = "loyalty_rewards")
data class LoyaltyReward(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "customer_id", nullable = false)
    val customerId: UUID,

    @Column(name = "earned_at", nullable = false, updatable = false)
    val earnedAt: Instant = Instant.now(),

    /** Quando foi resgatada; null enquanto disponível. */
    @Column(name = "redeemed_at")
    var redeemedAt: Instant? = null,

    /** Pedido em que a recompensa foi aplicada no resgate, se houver. */
    @Column(name = "redeemed_order_id")
    var redeemedOrderId: UUID? = null,

    @Column(name = "expires_at")
    val expiresAt: Instant? = null,
)
