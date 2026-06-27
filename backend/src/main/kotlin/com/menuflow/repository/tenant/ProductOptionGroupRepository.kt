package com.menuflow.repository.tenant

import com.menuflow.model.ProductOptionGroup
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ProductOptionGroupRepository : JpaRepository<ProductOptionGroup, UUID> {
    fun findByProductIdAndActiveTrue(productId: UUID): List<ProductOptionGroup>
}
