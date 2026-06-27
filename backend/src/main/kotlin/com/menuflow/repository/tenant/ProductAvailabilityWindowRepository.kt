package com.menuflow.repository.tenant

import com.menuflow.model.ProductAvailabilityWindow
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ProductAvailabilityWindowRepository : JpaRepository<ProductAvailabilityWindow, UUID> {
    fun findByProductId(productId: UUID): List<ProductAvailabilityWindow>
    fun deleteByProductId(productId: UUID)
}
