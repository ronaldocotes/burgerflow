package com.menuflow.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Lancamento no extrato de pontos de fidelidade (Fase 3.3). Tabela APPEND-ONLY:
 * nunca atualizada nem deletada — cada movimento de pontos vira uma linha nova.
 * Vive no banco do TENANT (db-per-tenant).
 *
 * points_delta positivo = crédito (ganhou); negativo = débito (resgate/ajuste).
 * reason: ORDER_PAID, REWARD_REDEEMED, MANUAL_ADJUST, EXPIRY.
 */
@Entity
@Table(name = "loyalty_transactions")
data class LoyaltyTransaction(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "customer_id", nullable = false)
    val customerId: UUID,

    /** Pedido que originou o lançamento; null em ajuste manual/expiração. */
    @Column(name = "order_id")
    val orderId: UUID? = null,

    @Column(name = "points_delta", nullable = false)
    val pointsDelta: Int,

    @Column(nullable = false, length = 50)
    val reason: String,

    @Column(length = 200)
    val description: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
