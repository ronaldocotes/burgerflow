package com.burgerflow.model

import jakarta.persistence.*
import java.math.BigDecimal
import java.util.UUID

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
    
    @Column(nullable = false, precision = 10, scale = 4)
    var quantity: BigDecimal,
    
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var unit: IngredientUnit = IngredientUnit.UNIT,
    
    @Column(name = "is_optional", nullable = false)
    var isOptional: Boolean = false,
    
    @Column(name = "can_remove", nullable = false)
    var canRemove: Boolean = false,
    
    @Column(name = "extra_price", precision = 10, scale = 2)
    var extraPrice: BigDecimal? = null,
    
    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0
)

data class ProductIngredientId(
    val productId: UUID,
    val ingredientId: UUID
)
