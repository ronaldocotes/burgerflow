package com.menuflow.repository.tenant

import com.menuflow.model.ProductSize
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ProductSizeRepository : JpaRepository<ProductSize, UUID> {
    fun findByProductIdAndActiveTrue(productId: UUID): List<ProductSize>
    fun existsByProductIdAndCode(productId: UUID, code: String): Boolean
}
