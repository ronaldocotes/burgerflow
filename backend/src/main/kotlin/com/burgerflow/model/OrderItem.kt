package com.burgerflow.model

import jakarta.persistence.*
import java.util.UUID

@Entity
@Table(name = "order_items")
data class OrderItem(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "order_id", nullable = false)
    var orderId: UUID,

    @Column(name = "product_id", nullable = false)
    var productId: UUID,

    @Column(name = "product_sku", nullable = false)
    var productSku: String,

    @Column(name = "product_name", nullable = false)
    var productName: String,

    @Column(nullable = false)
    var quantity: Int = 1,

    /** Unit price snapshot in centavos at order time. */
    @Column(name = "unit_price_cents", nullable = false)
    var unitPriceCents: Long = 0,

    /** Line total in centavos = unitPriceCents * quantity. */
    @Column(name = "total_price_cents", nullable = false)
    var totalPriceCents: Long = 0,

    @Column(name = "notes")
    var notes: String? = null,

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    var status: OrderItemStatus = OrderItemStatus.PENDING,

    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0,
)

enum class OrderItemStatus {
    PENDING,
    PREPARING,
    READY,
    DELIVERED,
    CANCELLED,
}
