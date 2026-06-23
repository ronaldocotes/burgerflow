package com.menuflow.dto

import com.menuflow.model.Order
import com.menuflow.model.OrderStatus
import java.time.Instant
import java.util.UUID

/** A single line on the KDS card. */
data class KdsItem(
    val productName: String,
    val quantity: Int,
    val notes: String?,
) {
    companion object {
        fun from(o: Order): List<KdsItem> =
            o.items.map { KdsItem(it.productName, it.quantity, it.notes) }
    }
}

/**
 * Payload broadcast to /topic/kds/{tenantSlug} whenever an order changes status.
 * Spec shape: { orderId, orderNumber, status, items[], changedAt }.
 */
data class KdsOrderEvent(
    val orderId: UUID,
    val orderNumber: String,
    val status: OrderStatus,
    val items: List<KdsItem>,
    val changedAt: Instant,
) {
    companion object {
        fun from(o: Order, changedAt: Instant = Instant.now()) = KdsOrderEvent(
            orderId = o.id!!,
            orderNumber = o.orderNumber,
            status = o.status,
            items = KdsItem.from(o),
            changedAt = changedAt,
        )
    }
}

/** Read model for GET /kds/orders — the active kitchen queue. */
data class KdsOrderView(
    val orderId: UUID,
    val orderNumber: String,
    val status: OrderStatus,
    val orderType: String,
    val tableNumber: String?,
    val items: List<KdsItem>,
    val estimatedPrepTimeMinutes: Int,
    val createdAt: Instant,
) {
    companion object {
        fun from(o: Order) = KdsOrderView(
            orderId = o.id!!,
            orderNumber = o.orderNumber,
            status = o.status,
            orderType = o.orderType.name,
            tableNumber = o.tableNumber,
            items = KdsItem.from(o),
            estimatedPrepTimeMinutes = o.estimatedPrepTimeMinutes,
            createdAt = o.createdAt,
        )
    }
}
