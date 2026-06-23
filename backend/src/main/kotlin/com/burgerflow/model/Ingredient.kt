package com.burgerflow.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Stock item consumed by products via [ProductIngredient].
 * stockQuantity is a Double (design decision: fractional units like 0.150 kg).
 * unitCost is money -> stored in centavos.
 */
@Entity
@Table(name = "ingredients")
data class Ingredient(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(nullable = false, unique = true)
    var name: String,

    @Column(nullable = false)
    var description: String = "",

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var unit: IngredientUnit = IngredientUnit.UNIT,

    /** Unit cost in centavos. */
    @Column(name = "unit_cost_cents", nullable = false)
    var unitCostCents: Long = 0,

    /** Current stock on hand (fractional units allowed). */
    @Column(name = "stock_quantity", nullable = false)
    var stockQuantity: Double = 0.0,

    @Column(name = "min_stock", nullable = false)
    var minStock: Double = 0.0,

    @Column(name = "active", nullable = false)
    var active: Boolean = true,

    @Column(name = "is_allergen", nullable = false)
    var isAllergen: Boolean = false,

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

    fun isLowStock(): Boolean = stockQuantity <= minStock
}

enum class IngredientUnit {
    UNIT,
    GRAM,
    KILOGRAM,
    MILLILITER,
    LITER,
    PIECE,
    BOX,
}
