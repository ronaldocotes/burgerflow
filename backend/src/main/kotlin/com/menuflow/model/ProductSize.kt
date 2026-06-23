package com.menuflow.model

import jakarta.persistence.*
import java.util.UUID

@Entity
@Table(name = "product_sizes")
data class ProductSize(
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,
    @Column(name = "product_id", nullable = false) var productId: UUID,
    @Column(nullable = false) var name: String,
    @Column(nullable = false) var code: String,
    @Column(name = "price_cents", nullable = false) var priceCents: Long,
    @Column(nullable = false) var active: Boolean = true,
    @Column(name = "display_order", nullable = false) var displayOrder: Int = 0,
)
