package com.burgerflow.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Order lives in the TENANT database. All monetary fields are in CENTAVOS.
 */
@Entity
@Table(name = "orders")
data class Order(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "order_number", nullable = false, unique = true)
    var orderNumber: String,

    @Column(name = "customer_id")
    var customerId: UUID? = null,

    @Column(name = "user_id")
    var userId: UUID? = null,

    @Column(name = "order_type", nullable = false)
    @Enumerated(EnumType.STRING)
    var orderType: OrderType = OrderType.DINE_IN,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var status: OrderStatus = OrderStatus.PENDING,

    @Column(name = "table_number")
    var tableNumber: String? = null,

    @Column(name = "notes")
    var notes: String? = null,

    @Column(name = "subtotal_cents", nullable = false)
    var subtotalCents: Long = 0,

    @Column(name = "discount_cents", nullable = false)
    var discountCents: Long = 0,

    @Column(name = "delivery_fee_cents", nullable = false)
    var deliveryFeeCents: Long = 0,

    @Column(name = "total_cents", nullable = false)
    var totalCents: Long = 0,

    @Column(name = "payment_method")
    @Enumerated(EnumType.STRING)
    var paymentMethod: PaymentMethod? = null,

    @Column(name = "payment_status", nullable = false)
    @Enumerated(EnumType.STRING)
    var paymentStatus: PaymentStatus = PaymentStatus.PENDING,

    @Column(name = "priority", nullable = false)
    @Enumerated(EnumType.STRING)
    var priority: OrderPriority = OrderPriority.NORMAL,

    @Column(name = "estimated_prep_time_minutes", nullable = false)
    var estimatedPrepTimeMinutes: Int = 15,

    @OneToMany(mappedBy = "orderId", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var items: MutableList<OrderItem> = mutableListOf(),

    @Version
    @Column(nullable = false)
    var version: Long = 0,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "completed_at")
    var completedAt: Instant? = null,

    @Column(name = "cancelled_at")
    var cancelledAt: Instant? = null,

    @Column(name = "cancelled_reason")
    var cancelledReason: String? = null,
) {
    @PreUpdate
    fun preUpdate() {
        updatedAt = Instant.now()
    }

    fun canBeCancelled(): Boolean =
        status in listOf(OrderStatus.PENDING, OrderStatus.PREPARING)
}

enum class OrderType {
    DINE_IN,
    TAKEAWAY,
    DELIVERY,
}

/**
 * Lifecycle per Sprint 1 spec: PENDING -> PREPARING -> READY -> DELIVERED,
 * with CANCELLED reachable from any non-terminal state.
 */
enum class OrderStatus {
    PENDING,
    PREPARING,
    READY,
    DELIVERED,
    CANCELLED,
}

enum class PaymentMethod {
    CASH,
    CREDIT_CARD,
    DEBIT_CARD,
    PIX,
    OTHER,
}

enum class PaymentStatus {
    PENDING,
    PAID,
    FAILED,
    REFUNDED,
}

enum class OrderPriority {
    LOW,
    NORMAL,
    HIGH,
    URGENT,
}
