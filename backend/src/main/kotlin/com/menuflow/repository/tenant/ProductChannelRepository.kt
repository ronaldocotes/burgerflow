package com.menuflow.repository.tenant

import com.menuflow.model.ProductChannel
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ProductChannelRepository : JpaRepository<ProductChannel, UUID> {
    fun findByProductId(productId: UUID): List<ProductChannel>
    fun deleteByProductId(productId: UUID)
}
