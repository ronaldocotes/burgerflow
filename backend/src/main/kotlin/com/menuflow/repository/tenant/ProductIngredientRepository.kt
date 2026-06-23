package com.menuflow.repository.tenant

import com.menuflow.model.ProductIngredient
import com.menuflow.model.ProductIngredientId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ProductIngredientRepository : JpaRepository<ProductIngredient, ProductIngredientId> {
    fun findByProductId(productId: UUID): List<ProductIngredient>
    fun findByProductIdIn(productIds: Collection<UUID>): List<ProductIngredient>
}
