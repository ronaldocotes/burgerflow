package com.burgerflow.dto

import com.burgerflow.model.OrderPriority
import com.burgerflow.model.OrderStatus
import com.burgerflow.model.OrderType
import com.burgerflow.model.PaymentMethod
import com.burgerflow.model.PaymentStatus
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

data class OrderResponse(
    val id: UUID,
    val orderNumber: String,
    val tenantId: UUID,
    val customerId: UUID? = null,
    val customerName: String? = null,
    val customerPhone: String? = null,
    val userId: UUID? = null,
    val userName: String? = null,
    val orderType: OrderType,
    val status: OrderStatus,
    val tableNumber: String? = null,
    val items: List<OrderItemResponse>,
    val subtotal: BigDecimal,
    val discount: BigDecimal,
    val deliveryFee: BigDecimal,
    val taxAmount: BigDecimal,
    val total: BigDecimal,
    val paymentMethod: PaymentMethod? = null,
    val paymentStatus: PaymentStatus,
    val paymentReference: String? = null,
    val isTakeaway: Boolean,
    val priority: OrderPriority,
    val estimatedPrepTimeMinutes: Int,
    val notes: String? = null,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val completedAt: LocalDateTime? = null
)

data class OrderItemResponse(
    val id: UUID,
    val productId: UUID,
    val productSku: String,
    val productName: String,
    val quantity: Int,
    val unitPrice: BigDecimal,
    val totalPrice: BigDecimal,
    val notes: String? = null,
    val status: String,
    val preparationStartedAt: LocalDateTime? = null,
    val preparationCompletedAt: LocalDateTime? = null,
    val customizations: List<ProductCustomizationResponse>? = null
)

data class ProductCustomizationResponse(
    val ingredientId: UUID,
    val ingredientName: String,
    val action: String,
    val quantity: Double? = null,
    val extraPrice: BigDecimal? = null
)

data class OrderListResponse(
    val content: List<OrderSummaryResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val last: Boolean
)

data class OrderSummaryResponse(
    val id: UUID,
    val orderNumber: String,
    val tenantId: UUID,
    val customerName: String? = null,
    val orderType: OrderType,
    val status: OrderStatus,
    val total: BigDecimal,
    val paymentStatus: PaymentStatus,
    val createdAt: LocalDateTime,
    val tableNumber: String? = null
)
