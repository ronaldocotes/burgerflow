package com.menuflow.repository.tenant

import com.menuflow.model.ProductOption
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ProductOptionRepository : JpaRepository<ProductOption, UUID> {
    fun findByGroupIdAndActiveTrue(groupId: UUID): List<ProductOption>
    fun findByGroupIdInAndActiveTrue(groupIds: Collection<UUID>): List<ProductOption>
}
