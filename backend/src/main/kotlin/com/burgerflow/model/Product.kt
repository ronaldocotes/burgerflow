package com.burgerflow.model

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "products")
data class Product(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,
    
    @Column(nullable = false)
    var tenantId: UUID,
    
    @Column(nullable = false)
    var categoryId: UUID,
    
    @Column(nullable = false, unique = true)
    var sku: String,
    
    @Column(nullable = false)
    var name: String,
    
    @Column(nullable = false)
    var description: String = "",
    
    @Column(nullable = false, precision = 10, scale = 2)
    var price: BigDecimal,
    
    @Column(name = "cost_price", precision = 10, scale = 2)
    var costPrice: BigDecimal? = null,
    
    @Column(name = "image_url")
    var imageUrl: String? = null,
    
    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,
    
    @Column(name = "is_available", nullable = false)
    var isAvailable: Boolean = true,
    
    @Column(name = "stock_quantity", nullable = false)
    var stockQuantity: Int = 0,
    
    @Column(name = "min_stock_level", nullable = false)
    var minStockLevel: Int = 0,
    
    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0,
    
    @Column(name = "preparation_time_minutes", nullable = false)
    var preparationTimeMinutes: Int = 10,
    
    @Column(name = "is_featured", nullable = false)
    var isFeatured: Boolean = false,
    
    @Column(name = "is_combo", nullable = false)
    var isCombo: Boolean = false,
    
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    
    @Column(name = "updated_at")
    var updatedAt: LocalDateTime = LocalDateTime.now()
) {
    @PreUpdate
    fun preUpdate() {
        updatedAt = LocalDateTime.now()
    }
    
    // Calculate profit margin
    fun getProfitMargin(): BigDecimal? {
        if (costPrice != null && costPrice.compareTo(BigDecimal.ZERO) > 0) {
            return ((price - costPrice) / costPrice) * BigDecimal(100)
        }
        return null
    }
    
    fun isLowStock(): Boolean {
        return stockQuantity <= minStockLevel
    }
}
