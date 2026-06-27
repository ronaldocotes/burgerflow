package com.menuflow.model

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

    @Column(name = "unit_price_cents", nullable = false)
    var unitPriceCents: Long = 0,

    @Column(name = "total_price_cents", nullable = false)
    var totalPriceCents: Long = 0,

    @Column(name = "notes")
    var notes: String? = null,

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    var status: OrderItemStatus = OrderItemStatus.PENDING,

    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0,

    // --- Variações (pizza e similares) ---
    @Column(name = "size_id") var sizeId: UUID? = null,
    @Column(name = "size_name") var sizeName: String? = null,
    @Column(name = "flavor1_id") var flavor1Id: UUID? = null,
    @Column(name = "flavor1_name") var flavor1Name: String? = null,
    @Column(name = "flavor2_id") var flavor2Id: UUID? = null,
    @Column(name = "flavor2_name") var flavor2Name: String? = null,
    @Column(name = "crust_type") @Enumerated(EnumType.STRING) var crustType: CrustType? = null,
    @Column(name = "dough_type")  @Enumerated(EnumType.STRING) var doughType: DoughType? = null,

    // Complementos escolhidos (snapshot). Mesmo padrão de Order.items: a FK é uma
    // coluna simples (orderItemId), setada manualmente no service antes do persist
    // em cascata (o orderItemId só existe depois que o item ganha id).
    @OneToMany(mappedBy = "orderItemId", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var options: MutableList<OrderItemOption> = mutableListOf(),
)

enum class OrderItemStatus {
    PENDING, PREPARING, READY, DELIVERED, CANCELLED,
}
