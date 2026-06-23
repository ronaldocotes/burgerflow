package com.burgerflow.model

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "ingredients")
data class Ingredient(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,
    
    @Column(nullable = false)
    var tenantId: UUID,
    
    @Column(nullable = false, unique = true)
    var name: String,
    
    @Column(nullable = false)
    var description: String = "",
    
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var unit: IngredientUnit = IngredientUnit.UNIT,
    
    @Column(name = "unit_cost", nullable = false, precision = 10, scale = 4)
    var unitCost: BigDecimal,
    
    @Column(name = "current_stock", nullable = false, precision = 10, scale = 4)
    var currentStock: BigDecimal = BigDecimal.ZERO,
    
    @Column(name = "min_stock", nullable = false, precision = 10, scale = 4)
    var minStock: BigDecimal = BigDecimal.ZERO,
    
    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,
    
    @Column(name = "is_allergen", nullable = false)
    var isAllergen: Boolean = false,
    
    @Column(name = "allergen_info")
    var allergenInfo: String? = null,
    
    @Column(name = "category")
    var category: String? = null,
    
    @Column(name = "supplier_id")
    var supplierId: UUID? = null,
    
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    
    @Column(name = "updated_at")
    var updatedAt: LocalDateTime = LocalDateTime.now()
) {
    @PreUpdate
    fun preUpdate() {
        updatedAt = LocalDateTime.now()
    }
    
    fun isLowStock(): Boolean {
        return currentStock <= minStock
    }
}

enum class IngredientUnit {
    UNIT,
    GRAM,
    KILOGRAM,
    MILLILITER,
    LITER,
    PIECE,
    BOX
}
