package com.burgerflow.repository

import com.burgerflow.model.Product
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ProductRepository : JpaRepository<Product, UUID> {
    
    fun findByTenantId(tenantId: UUID): List<Product>
    
    fun findByTenantIdAndIsActive(tenantId: UUID, isActive: Boolean): List<Product>
    
    fun findByTenantIdAndIsAvailable(tenantId: UUID, isAvailable: Boolean): List<Product>
    
    fun findByTenantIdAndCategoryId(tenantId: UUID, categoryId: UUID): List<Product>
    
    fun findByTenantIdAndSku(tenantId: UUID, sku: String): Product?
    
    fun findByTenantIdAndIsFeatured(tenantId: UUID, isFeatured: Boolean): List<Product>
    
    fun existsByTenantIdAndSku(tenantId: UUID, sku: String): Boolean
    
    fun findByTenantIdAndNameContainingIgnoreCase(tenantId: UUID, name: String): List<Product>
    
    @Query("SELECT p FROM Product p WHERE p.tenantId = :tenantId AND (p.name LIKE %:query% OR p.description LIKE %:query% OR p.sku LIKE %:query%)")
    fun searchProducts(tenantId: UUID, query: String): List<Product>
    
    fun findByTenantIdAndStockQuantityLessThanEqual(tenantId: UUID, stock: Int): List<Product>
    
    fun findByTenantIdOrderByDisplayOrderAsc(tenantId: UUID): List<Product>
    
    fun findByTenantIdAndCategoryIdOrderByDisplayOrderAsc(tenantId: UUID, categoryId: UUID): List<Product>
    
    fun findByTenantId(tenantId: UUID, pageable: Pageable): Page<Product>
    
    @Query("SELECT COUNT(p) > 0 FROM Product p WHERE p.tenantId = :tenantId AND p.isActive = true")
    fun existsActiveProductByTenant(tenantId: UUID): Boolean
    
    fun deleteByTenantId(tenantId: UUID): Int
}
