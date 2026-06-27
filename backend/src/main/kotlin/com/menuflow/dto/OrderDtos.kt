package com.menuflow.dto

import com.menuflow.model.Order
import com.menuflow.model.OrderItem
import com.menuflow.model.OrderItemOption
import com.menuflow.model.OrderStatus
import com.menuflow.model.OrderType
import com.menuflow.model.PaymentMethod
import com.menuflow.model.PaymentStatus
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive
import java.time.Instant
import java.util.UUID

data class OrderItemRequest(
    val productId: UUID,
    @field:Positive @field:Max(999) val quantity: Int = 1,
    val notes: String? = null,
    val sizeId: UUID? = null,
    val flavor1Id: UUID? = null,
    val flavor2Id: UUID? = null,
    val crustType: String? = null,
    val doughType: String? = null,
    /** Opções de complemento escolhidas (ids do catálogo de option groups). */
    val optionIds: List<UUID> = emptyList(),
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

/**
 * Carrinho enxuto para cotação (POST /orders/quote): só o que afeta o total.
 * Não aceita customerId/tableNumber/paymentMethod/notes porque o quote não cria
 * pedido — é cálculo. Reutiliza OrderItemRequest, então o snapshot de variação/
 * complementos é idêntico ao do create e os valores cotados batem.
 */
data class QuoteRequest(
    val orderType: OrderType = OrderType.DINE_IN,
    @field:NotEmpty @field:Valid val items: List<OrderItemRequest>,
    val deliveryFeeCents: Long = 0,
    val discountCents: Long = 0,
)

data class QuoteItemResponse(
    val productId: UUID,
    val productSku: String,
    val productName: String,
    val quantity: Int,
    val unitPriceCents: Long,
    val totalPriceCents: Long,
    val sizeId: UUID? = null,
    val sizeName: String? = null,
    val flavor1Id: UUID? = null,
    val flavor1Name: String? = null,
    val flavor2Id: UUID? = null,
    val flavor2Name: String? = null,
    val crustType: String? = null,
    val doughType: String? = null,
    val options: List<OrderItemOptionView> = emptyList(),
) {
    companion object {
        fun from(i: OrderItem, opts: List<OrderItemOption>) = QuoteItemResponse(
            productId = i.productId,
            productSku = i.productSku,
            productName = i.productName,
            quantity = i.quantity,
            unitPriceCents = i.unitPriceCents,
            totalPriceCents = i.totalPriceCents,
            sizeId = i.sizeId,
            sizeName = i.sizeName,
            flavor1Id = i.flavor1Id,
            flavor1Name = i.flavor1Name,
            flavor2Id = i.flavor2Id,
            flavor2Name = i.flavor2Name,
            crustType = i.crustType?.name,
            doughType = i.doughType?.name,
            options = opts.map {
                OrderItemOptionView(it.optionId, it.groupName, it.optionName, it.priceCents)
            },
        )
    }
}

data class QuoteResponse(
    val items: List<QuoteItemResponse>,
    val subtotalCents: Long,
    val discountCents: Long,
    val deliveryFeeCents: Long,
    val totalCents: Long,
)

data class OrderItemOptionView(
    val optionId: UUID,
    val groupName: String,
    val optionName: String,
    val priceCents: Long,
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
    val sizeId: UUID? = null,
    val sizeName: String? = null,
    val flavor1Id: UUID? = null,
    val flavor1Name: String? = null,
    val flavor2Id: UUID? = null,
    val flavor2Name: String? = null,
    val crustType: String? = null,
    val doughType: String? = null,
    val options: List<OrderItemOptionView> = emptyList(),
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
            sizeId = i.sizeId,
            sizeName = i.sizeName,
            flavor1Id = i.flavor1Id,
            flavor1Name = i.flavor1Name,
            flavor2Id = i.flavor2Id,
            flavor2Name = i.flavor2Name,
            crustType = i.crustType?.name,
            doughType = i.doughType?.name,
            options = i.options.map {
                OrderItemOptionView(it.optionId, it.groupName, it.optionName, it.priceCents)
            },
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
