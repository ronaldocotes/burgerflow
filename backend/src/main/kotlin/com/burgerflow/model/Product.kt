package com.burgerflow.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Product lives in the TENANT database. No tenantId discriminator is needed:
 * isolation is physical (one DB per tenant). Money is stored in CENTAVOS (Long),
 * never float/BigDecimal-as-currency — domain rule (conhecimento Seç.10).
 */
@Entity
@Table(name = "products")
data class Product(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "category_id", nullable = false)
    var categoryId: UUID,

    @Column(nullable = false, unique = true)
    var sku: String,

    @Column(nullable = false)
    var name: String,

    @Column(nullable = false)
    var description: String = "",

    /** Sale price in centavos. */
    @Column(name = "price_cents", nullable = false)
    var priceCents: Long,

    /** Cost price in centavos (optional). */
    @Column(name = "cost_price_cents")
    var costPriceCents: Long? = null,

    @Column(name = "image_url")
    var imageUrl: String? = null,

    /** Soft-delete flag. */
    @Column(name = "active", nullable = false)
    var active: Boolean = true,

    @Column(name = "is_available", nullable = false)
    var isAvailable: Boolean = true,

    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0,

    @Column(name = "preparation_time_minutes", nullable = false)
    var preparationTimeMinutes: Int = 10,

    @Column(name = "is_featured", nullable = false)
    var isFeatured: Boolean = false,

    @Version
    @Column(nullable = false)
    var version: Long = 0,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun preUpdate() {
        updatedAt = Instant.now()
    }
}
