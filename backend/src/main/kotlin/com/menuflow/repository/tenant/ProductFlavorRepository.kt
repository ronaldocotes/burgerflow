package com.menuflow.repository.tenant

import com.menuflow.model.ProductFlavor
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ProductFlavorRepository : JpaRepository<ProductFlavor, UUID> {
    fun findByProductIdAndActiveTrue(productId: UUID): List<ProductFlavor>
}
