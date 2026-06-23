package com.burgerflow.model

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "orders")
data class Order(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,
    
    @Column(nullable = false)
    var tenantId: UUID,
    
    @Column(name = "order_number", nullable = false, unique = true)
    var orderNumber: String,
    
    @Column(nullable = false)
    var customerId: UUID? = null,
    
    @Column(nullable = false)
    var userId: UUID? = null,
    
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var orderType: OrderType = OrderType.DINE_IN,
    
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var status: OrderStatus = OrderStatus.PENDING,
    
    @Column(name = "table_number")
    var tableNumber: String? = null,
    
    @Column(name = "notes")
    var notes: String? = null,
    
    @Column(name = "subtotal", nullable = false, precision = 12, scale = 2)
    var subtotal: BigDecimal = BigDecimal.ZERO,
    
    @Column(name = "discount", precision = 12, scale = 2)
    var discount: BigDecimal = BigDecimal.ZERO,
    
    @Column(name = "delivery_fee", precision = 12, scale = 2)
    var deliveryFee: BigDecimal = BigDecimal.ZERO,
    
    @Column(name = "tax_amount", precision = 12, scale = 2)
    var taxAmount: BigDecimal = BigDecimal.ZERO,
    
    @Column(name = "total", nullable = false, precision = 12, scale = 2)
    var total: BigDecimal = BigDecimal.ZERO,
    
    @Column(name = "payment_method")
    @Enumerated(EnumType.STRING)
    var paymentMethod: PaymentMethod? = null,
    
    @Column(name = "payment_status")
    @Enumerated(EnumType.STRING)
    var paymentStatus: PaymentStatus = PaymentStatus.PENDING,
    
    @Column(name = "payment_reference")
    var paymentReference: String? = null,
    
    @Column(name = "is_takeaway", nullable = false)
    var isTakeaway: Boolean = false,
    
    @Column(name = "priority", nullable = false)
    @Enumerated(EnumType.STRING)
    var priority: OrderPriority = OrderPriority.NORMAL,
    
    @Column(name = "estimated_prep_time_minutes", nullable = false)
    var estimatedPrepTimeMinutes: Int = 15,
    
    @Column(name = "idempotency_key", unique = true)
    var idempotencyKey: String? = null,
    
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    
    @Column(name = "updated_at")
    var updatedAt: LocalDateTime = LocalDateTime.now(),
    
    @Column(name = "completed_at")
    var completedAt: LocalDateTime? = null,
    
    @Column(name = "cancelled_at")
    var cancelledAt: LocalDateTime? = null,
    
    @Column(name = "cancelled_reason")
    var cancelledReason: String? = null
) {
    @PreUpdate
    fun preUpdate() {
        updatedAt = LocalDateTime.now()
    }
    
    fun calculateTotal(): BigDecimal {
        return subtotal - discount + deliveryFee + taxAmount
    }
    
    fun isPaid(): Boolean {
        return paymentStatus == PaymentStatus.PAID
    }
    
    fun isCompleted(): Boolean {
        return status == OrderStatus.COMPLETED
    }
    
    fun isCancelled(): Boolean {
        return status == OrderStatus.CANCELLED
    }
    
    fun canBeCancelled(): Boolean {
        return status in listOf(OrderStatus.PENDING, OrderStatus.IN_PREPARATION)
    }
}

enum class OrderType {
    DINE_IN,
    TAKEAWAY,
    DELIVERY
}

enum class OrderStatus {
    PENDING,
    IN_PREPARATION,
    READY_FOR_DELIVERY,
    IN_DELIVERY,
    COMPLETED,
    CANCELLED
}

enum class PaymentMethod {
    CASH,
    CREDIT_CARD,
    DEBIT_CARD,
    PIX,
    MERCADO_PAGO,
    OTHER
}

enum class PaymentStatus {
    PENDING,
    PAID,
    FAILED,
    REFUNDED,
    PARTIALLY_REFUNDED
}

enum class OrderPriority {
    LOW,
    NORMAL,
    HIGH,
    URGENT
}
