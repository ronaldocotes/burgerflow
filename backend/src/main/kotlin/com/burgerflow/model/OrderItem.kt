package com.burgerflow.model

import jakarta.persistence.*
import java.math.BigDecimal
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
    
    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    var unitPrice: BigDecimal = BigDecimal.ZERO,
    
    @Column(name = "total_price", nullable = false, precision = 12, scale = 2)
    var totalPrice: BigDecimal = BigDecimal.ZERO,
    
    @Column(name = "notes")
    var notes: String? = null,
    
    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    var status: OrderItemStatus = OrderItemStatus.PENDING,
    
    @Column(name = "preparation_started_at")
    var preparationStartedAt: java.time.LocalDateTime? = null,
    
    @Column(name = "preparation_completed_at")
    var preparationCompletedAt: java.time.LocalDateTime? = null,
    
    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0,
    
    @Column(name = "is_combo", nullable = false)
    var isCombo: Boolean = false,
    
    @Column(name = "parent_item_id")
    var parentItemId: UUID? = null
) {
    fun calculateTotalPrice(): BigDecimal {
        return unitPrice * BigDecimal(quantity)
    }
    
    fun isCompleted(): Boolean {
        return status == OrderItemStatus.COMPLETED
    }
    
    fun isInPreparation(): Boolean {
        return status == OrderItemStatus.IN_PREPARATION
    }
}

enum class OrderItemStatus {
    PENDING,
    IN_PREPARATION,
    READY,
    COMPLETED,
    CANCELLED
}
