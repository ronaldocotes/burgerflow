package com.burgerflow.model

import jakarta.persistence.*
import java.io.Serializable
import java.util.UUID

/**
 * Ficha técnica line: how much of an [Ingredient] one unit of a [Product]
 * consumes. Used to decrement stock atomically when an order is placed.
 */
@Entity
@Table(name = "product_ingredients")
@IdClass(ProductIngredientId::class)
data class ProductIngredient(
    @Id
    @Column(name = "product_id", nullable = false)
    var productId: UUID,

    @Id
    @Column(name = "ingredient_id", nullable = false)
    var ingredientId: UUID,

    /** Quantity of the ingredient consumed per unit of product. */
    @Column(nullable = false)
    var quantity: Double,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var unit: IngredientUnit = IngredientUnit.UNIT,

    @Column(name = "is_optional", nullable = false)
    var isOptional: Boolean = false,

    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0,
)

data class ProductIngredientId(
    var productId: UUID = UUID(0, 0),
    var ingredientId: UUID = UUID(0, 0),
) : Serializable
