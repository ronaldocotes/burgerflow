package com.menuflow.repository.tenant

import com.menuflow.model.ProductOptionGroup
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ProductOptionGroupRepository : JpaRepository<ProductOptionGroup, UUID> {
    fun findByProductIdAndActiveTrue(productId: UUID): List<ProductOptionGroup>

    /** Carrega grupos ativos de varios produtos de uma vez (evita N+1 no cardapio publico). */
    fun findByProductIdInAndActiveTrue(productIds: Collection<UUID>): List<ProductOptionGroup>
}
