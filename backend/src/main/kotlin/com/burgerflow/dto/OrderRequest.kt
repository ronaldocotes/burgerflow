package com.burgerflow.dto

import com.burgerflow.model.OrderPriority
import com.burgerflow.model.OrderType
import com.burgerflow.model.PaymentMethod
import java.util.UUID

data class OrderRequest(
    val tenantId: UUID,
    val customerId: UUID? = null,
    val orderType: OrderType,
    val tableNumber: String? = null,
    val items: List<OrderItemRequest>,
    val notes: String? = null,
    val paymentMethod: PaymentMethod? = null,
    val isTakeaway: Boolean = false,
    val priority: OrderPriority = OrderPriority.NORMAL,
    val idempotencyKey: String? = null
)

data class OrderItemRequest(
    val productId: UUID,
    val quantity: Int = 1,
    val notes: String? = null,
    val customizations: List<ProductCustomizationRequest>? = null
)

data class ProductCustomizationRequest(
    val ingredientId: UUID,
    val action: CustomizationAction,
    val quantity: Double? = null
)

enum class CustomizationAction {
    ADD,
    REMOVE,
    REPLACE
}
