package com.menuflow.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Cupom de desconto (Fase 3.2). Vive no banco do TENANT (db-per-tenant), então não
 * precisa de coluna de escopo. Dinheiro SEMPRE em centavos.
 *
 * [code] é a chave natural (UNIQUE), guardada em maiúsculas+trim pelo serviço para
 * que o lookup seja case-insensitive. [discountValue] depende do [discountType]:
 *  - FIXED   -> valor do desconto em centavos;
 *  - PERCENT -> percentual multiplicado por 100 (ex.: 1500 = 15,00%).
 */
@Entity
@Table(name = "coupons")
data class Coupon(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "code", nullable = false, length = 50, unique = true)
    var code: String,

    @Column(name = "description", length = 200)
    var description: String? = null,

    @Column(name = "discount_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    var discountType: DiscountType,

    @Column(name = "discount_value", nullable = false)
    var discountValue: Long,

    @Column(name = "min_order_cents", nullable = false)
    var minOrderCents: Long = 0,

    /** Limite global de usos; null = ilimitado. */
    @Column(name = "max_uses")
    var maxUses: Int? = null,

    /** Limite de usos por telefone do cliente. */
    @Column(name = "max_uses_per_customer", nullable = false)
    var maxUsesPerCustomer: Int = 1,

    @Column(name = "valid_from", nullable = false)
    var validFrom: Instant,

    @Column(name = "valid_until", nullable = false)
    var validUntil: Instant,

    @Column(name = "active", nullable = false)
    var active: Boolean = true,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun preUpdate() {
        updatedAt = Instant.now()
    }
}

/** Tipo de desconto do cupom. */
enum class DiscountType {
    FIXED,   // valor fixo em centavos
    PERCENT, // percentual x 100 (1500 = 15%)
}

/**
 * Registro de uso (redenção) de um cupom num pedido. A contagem destes registros é
 * a fonte de verdade dos limites maxUses (global) e maxUsesPerCustomer (por telefone).
 */
@Entity
@Table(name = "coupon_redemptions")
data class CouponRedemption(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "coupon_id", nullable = false)
    val couponId: UUID,

    @Column(name = "order_id", nullable = false)
    val orderId: UUID,

    @Column(name = "customer_phone", length = 20)
    val customerPhone: String? = null,

    @Column(name = "discount_applied_cents", nullable = false)
    val discountAppliedCents: Long,

    @Column(name = "redeemed_at", nullable = false, updatable = false)
    val redeemedAt: Instant = Instant.now(),
)
