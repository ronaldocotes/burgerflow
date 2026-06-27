package com.menuflow.repository.tenant

import com.menuflow.model.CrustType
import com.menuflow.model.ProductCrustPrice
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ProductCrustPriceRepository : JpaRepository<ProductCrustPrice, UUID> {
    fun findByProductId(productId: UUID): List<ProductCrustPrice>
    fun findByProductIdAndCrustType(productId: UUID, crustType: CrustType): ProductCrustPrice?
}
