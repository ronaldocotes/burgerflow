package com.burgerflow.repository.tenant

import com.burgerflow.model.ProductIngredient
import com.burgerflow.model.ProductIngredientId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ProductIngredientRepository : JpaRepository<ProductIngredient, ProductIngredientId> {
    fun findByProductId(productId: UUID): List<ProductIngredient>
    fun findByProductIdIn(productIds: Collection<UUID>): List<ProductIngredient>
}
