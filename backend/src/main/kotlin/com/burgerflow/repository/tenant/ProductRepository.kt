package com.burgerflow.repository.tenant

import com.burgerflow.model.Product
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ProductRepository : JpaRepository<Product, UUID> {
    fun findByActiveTrue(pageable: Pageable): Page<Product>
    fun findByIdAndActiveTrue(id: UUID): Product?
    fun existsBySku(sku: String): Boolean
    fun findBySku(sku: String): Product?
}
