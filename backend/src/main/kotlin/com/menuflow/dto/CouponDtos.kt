package com.menuflow.dto

import com.menuflow.model.Coupon
import com.menuflow.model.CouponRedemption
import com.menuflow.model.DiscountType
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Criação de cupom. discountValue é em centavos (FIXED) ou %x100 (PERCENT, ex.:
 * 1500 = 15%); a coerência tipo<->valor (PERCENT em 1..10000) é validada no serviço.
 */
data class CouponCreateRequest(
    @field:NotBlank @field:Size(max = 50) val code: String,
    @field:Size(max = 200) val description: String? = null,
    val discountType: DiscountType,
    @field:Positive val discountValue: Long,
    @field:PositiveOrZero val minOrderCents: Long = 0,
    @field:Positive val maxUses: Int? = null,
    @field:Positive val maxUsesPerCustomer: Int = 1,
    val validFrom: Instant,
    val validUntil: Instant,
    val active: Boolean = true,
)

/** Edição de cupom. O code (chave natural) é imutável após criado. */
data class CouponUpdateRequest(
    @field:Size(max = 200) val description: String? = null,
    val discountType: DiscountType,
    @field:Positive val discountValue: Long,
    @field:PositiveOrZero val minOrderCents: Long = 0,
    @field:Positive val maxUses: Int? = null,
    @field:Positive val maxUsesPerCustomer: Int = 1,
    val validFrom: Instant,
    val validUntil: Instant,
    val active: Boolean = true,
)

data class CouponResponse(
    val id: UUID,
    val code: String,
    val description: String?,
    val discountType: DiscountType,
    val discountValue: Long,
    val minOrderCents: Long,
    val maxUses: Int?,
    val maxUsesPerCustomer: Int,
    val validFrom: Instant,
    val validUntil: Instant,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(c: Coupon) = CouponResponse(
            id = c.id!!,
            code = c.code,
            description = c.description,
            discountType = c.discountType,
            discountValue = c.discountValue,
            minOrderCents = c.minOrderCents,
            maxUses = c.maxUses,
            maxUsesPerCustomer = c.maxUsesPerCustomer,
            validFrom = c.validFrom,
            validUntil = c.validUntil,
            active = c.active,
            createdAt = c.createdAt,
            updatedAt = c.updatedAt,
        )
    }
}

data class CouponRedemptionResponse(
    val id: UUID,
    val couponId: UUID,
    val orderId: UUID,
    val customerPhone: String?,
    val discountAppliedCents: Long,
    val redeemedAt: Instant,
) {
    companion object {
        fun from(r: CouponRedemption) = CouponRedemptionResponse(
            id = r.id!!,
            couponId = r.couponId,
            orderId = r.orderId,
            customerPhone = r.customerPhone,
            discountAppliedCents = r.discountAppliedCents,
            redeemedAt = r.redeemedAt,
        )
    }
}

/** Pré-checagem pública do cupom (POST /public/{slug}/apply-coupon). */
data class ApplyCouponRequest(
    @field:NotBlank @field:Size(max = 50) val code: String,
    @field:PositiveOrZero val subtotalCents: Long,
    @field:Size(max = 20) val customerPhone: String? = null,
)

data class ApplyCouponResponse(
    val valid: Boolean,
    val discountCents: Long,
    val description: String?,
)

/** Entrada do ranking de cupons no sumário de performance. */
data class TopCouponEntry(
    val code: String,
    val redemptions: Long,
    val discountCents: Long,
)

/**
 * Sumário de performance dos cupons num período (Fase 3.2).
 * Expõe total de redenções, desconto concedido e top cupons para o
 * painel de gestão avaliar a eficácia das promoções.
 * Dinheiro em centavos.
 */
data class CouponSummaryResponse(
    val from: LocalDate,
    val to: LocalDate,
    val totalRedemptions: Long,
    val totalDiscountCents: Long,
    val topCoupons: List<TopCouponEntry>,
)
