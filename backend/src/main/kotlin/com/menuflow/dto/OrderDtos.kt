package com.menuflow.dto

import com.menuflow.model.Order
import com.menuflow.model.OrderItem
import com.menuflow.model.OrderStatus
import com.menuflow.model.OrderType
import com.menuflow.model.PaymentMethod
import com.menuflow.model.PaymentStatus
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive
import java.time.Instant
import java.util.UUID

data class OrderItemRequest(
    val productId: UUID,
    @field:Positive val quantity: Int = 1,
    val notes: String? = null,
    val sizeId: UUID? = null,
    val flavor1Id: UUID? = null,
    val flavor2Id: UUID? = null,
    val crustType: String? = null,
    val doughType: String? = null,
)

data class OrderCreateRequest(
    val customerId: UUID? = null,
    val orderType: OrderType = OrderType.DINE_IN,
    val tableNumber: String? = null,
    @field:NotEmpty @field:Valid val items: List<OrderItemRequest>,
    val notes: String? = null,
    val paymentMethod: PaymentMethod? = null,
    val deliveryFeeCents: Long = 0,
    val discountCents: Long = 0,
)

data class OrderStatusUpdateRequest(
    val status: OrderStatus,
    val reason: String? = null,
)

data class OrderItemResponse(
    val id: UUID,
    val productId: UUID,
    val productSku: String,
    val productName: String,
    val quantity: Int,
    val unitPriceCents: Long,
    val totalPriceCents: Long,
    val notes: String?,
    val status: String,
) {
    companion object {
        fun from(i: OrderItem) = OrderItemResponse(
            id = i.id!!,
            productId = i.productId,
            productSku = i.productSku,
            productName = i.productName,
            quantity = i.quantity,
            unitPriceCents = i.unitPriceCents,
            totalPriceCents = i.totalPriceCents,
            notes = i.notes,
            status = i.status.name,
        )
    }
}

data class OrderResponse(
    val id: UUID,
    val orderNumber: String,
    val customerId: UUID?,
    val userId: UUID?,
    val orderType: OrderType,
    val status: OrderStatus,
    val tableNumber: String?,
    val items: List<OrderItemResponse>,
    val subtotalCents: Long,
    val discountCents: Long,
    val deliveryFeeCents: Long,
    val totalCents: Long,
    val paymentMethod: PaymentMethod?,
    val paymentStatus: PaymentStatus,
    val priority: String,
    val estimatedPrepTimeMinutes: Int,
    val notes: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant?,
) {
    companion object {
        fun from(o: Order) = OrderResponse(
            id = o.id!!,
            orderNumber = o.orderNumber,
            customerId = o.customerId,
            userId = o.userId,
            orderType = o.orderType,
            status = o.status,
            tableNumber = o.tableNumber,
            items = o.items.map { OrderItemResponse.from(it) },
            subtotalCents = o.subtotalCents,
            discountCents = o.discountCents,
            deliveryFeeCents = o.deliveryFeeCents,
            totalCents = o.totalCents,
            paymentMethod = o.paymentMethod,
            paymentStatus = o.paymentStatus,
            priority = o.priority.name,
            estimatedPrepTimeMinutes = o.estimatedPrepTimeMinutes,
            notes = o.notes,
            createdAt = o.createdAt,
            updatedAt = o.updatedAt,
            completedAt = o.completedAt,
        )
    }
}
