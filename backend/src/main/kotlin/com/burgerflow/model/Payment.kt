package com.burgerflow.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * A payment registered against an order at the PDV (Sprint 2). Lives in the
 * TENANT database. All monetary fields are in CENTAVOS (never float).
 *
 * [changeCents] = troco = amountPaidCents - order total, clamped at >= 0. It is
 * only meaningful for CASH; for CARD/PIX it is 0 (amount paid equals the total).
 */
@Entity
@Table(name = "payments")
data class Payment(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "order_id", nullable = false)
    var orderId: UUID,

    @Column(name = "method", nullable = false)
    @Enumerated(EnumType.STRING)
    var method: PdvPaymentMethod,

    /** Amount tendered by the customer, in centavos. */
    @Column(name = "amount_paid_cents", nullable = false)
    var amountPaidCents: Long,

    /** Change due (troco) in centavos; 0 for non-cash. */
    @Column(name = "change_cents", nullable = false)
    var changeCents: Long = 0,

    @Column(name = "paid_at", nullable = false, updatable = false)
    val paidAt: Instant = Instant.now(),
)

/**
 * Payment methods accepted at the PDV (Sprint 2 spec: CASH|CARD|PIX). Kept
 * separate from the broader [PaymentMethod] used on the Order so the PDV contract
 * stays exactly as specified.
 */
enum class PdvPaymentMethod {
    CASH,
    CARD,
    PIX,
}
