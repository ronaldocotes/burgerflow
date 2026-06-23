package com.burgerflow.dto

import com.burgerflow.model.OrderType
import com.burgerflow.model.Payment
import com.burgerflow.model.PdvPaymentMethod
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.PositiveOrZero
import java.time.Instant
import java.util.UUID

/**
 * Sales channel at the PDV. Mapped to the order's [OrderType]. Note the spec uses
 * TAKEOUT while the domain enum uses TAKEAWAY — they are the same thing.
 */
enum class PdvChannel {
    DINE_IN,
    TAKEOUT,
    DELIVERY,
    ;

    fun toOrderType(): OrderType = when (this) {
        DINE_IN -> OrderType.DINE_IN
        TAKEOUT -> OrderType.TAKEAWAY
        DELIVERY -> OrderType.DELIVERY
    }
}

data class PdvOrderCreateRequest(
    val tableNumber: String? = null,
    @field:NotEmpty @field:Valid
    val items: List<OrderItemRequest>,
    val channel: PdvChannel = PdvChannel.DINE_IN,
)

data class PdvPaymentRequest(
    val method: PdvPaymentMethod,
    /** Amount tendered in centavos; must be >= the order total. */
    @field:PositiveOrZero
    val amountPaidCents: Long,
)

data class PaymentResponse(
    val id: UUID,
    val orderId: UUID,
    val method: PdvPaymentMethod,
    val amountPaidCents: Long,
    val changeCents: Long,
    val totalCents: Long,
    val paidAt: Instant,
) {
    companion object {
        fun from(p: Payment, totalCents: Long) = PaymentResponse(
            id = p.id!!,
            orderId = p.orderId,
            method = p.method,
            amountPaidCents = p.amountPaidCents,
            changeCents = p.changeCents,
            totalCents = totalCents,
            paidAt = p.paidAt,
        )
    }
}
