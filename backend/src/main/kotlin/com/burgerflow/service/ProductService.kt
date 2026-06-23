package com.burgerflow.service

import com.burgerflow.exception.ResourceNotFoundException
import com.burgerflow.model.Product
import com.burgerflow.repository.ProductRepository
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ProductService(private val productRepository: ProductRepository) {
    
    @Transactional
    fun createProduct(product: Product): Product {
        // Validate SKU uniqueness for tenant
        if (productRepository.existsByTenantIdAndSku(product.tenantId, product.sku)) {
            throw IllegalArgumentException("Product with SKU ${product.sku} already exists for this tenant")
        }
        
        // Set default values
        val productToSave = product.copy(
            id = null, // Let database generate ID
            isActive = true,
            isAvailable = true
        )
        
        return productRepository.save(productToSave)
    }
    
    @Transactional(readOnly = true)
    fun getProductById(productId: UUID): Product {
        return productRepository.findById(productId)
            .orElseThrow { ResourceNotFoundException("Product not found: $productId") }
    }
    
    @Transactional(readOnly = true)
    @Cacheable(value = ["products"], key = "#tenantId + '-all-' + #pageable.pageNumber + '-' + #pageable.pageSize")
    fun getProductsByTenant(tenantId: UUID, includeInactive: Boolean, pageable: Pageable): Page<Product> {
        return if (includeInactive) {
            productRepository.findByTenantId(tenantId, pageable)
        } else {
            productRepository.findByTenantIdAndIsActive(tenantId, true, pageable)
        }
    }
    
    @Transactional(readOnly = true)
    @Cacheable(value = ["products"], key = "#tenantId + '-available-' + #pageable.pageNumber + '-' + #pageable.pageSize")
    fun getAvailableProductsByTenant(tenantId: UUID, pageable: Pageable): Page<Product> {
        return productRepository.findByTenantIdAndIsAvailable(tenantId, true, pageable)
    }
    
    @Transactional(readOnly = true)
    fun getProductsByCategory(tenantId: UUID, categoryId: UUID, pageable: Pageable): Page<Product> {
        return productRepository.findByTenantIdAndCategoryId(tenantId, categoryId, pageable)
    }
    
    @Transactional(readOnly = true)
    fun getFeaturedProductsByTenant(tenantId: UUID, pageable: Pageable): Page<Product> {
        return productRepository.findByTenantIdAndIsFeatured(tenantId, true, pageable)
    }
    
    @Transactional(readOnly = true)
    fun getLowStockProducts(tenantId: UUID, pageable: Pageable): Page<Product> {
        return productRepository.findByTenantIdAndStockQuantityLessThanEqual(
            tenantId,
            10, // Threshold for low stock
            pageable
        )
    }
    
    @Transactional(readOnly = true)
    fun searchProducts(tenantId: UUID, query: String, pageable: Pageable): Page<Product> {
        val products = productRepository.searchProducts(tenantId, "%$query%")
        // Convert to page - this is a simplified approach
        // In a real application, you'd use a proper query with pagination
        val start = pageable.pageNumber * pageable.pageSize
        val end = (start + pageable.pageSize).coerceAtMost(products.size)
        val pageContent = products.subList(start, end)
        return Page.of(pageContent, pageable, products.size.toLong())
    }
    
    @Transactional
    @CacheEvict(value = ["products"], allEntries = true)
    fun updateProduct(productId: UUID, product: Product): Product {
        val existingProduct = productRepository.findById(productId)
            .orElseThrow { ResourceNotFoundException("Product not found: $productId") }
        
        // Validate SKU uniqueness for tenant if SKU changed
        if (existingProduct.sku != product.sku && 
            productRepository.existsByTenantIdAndSku(product.tenantId, product.sku)) {
            throw IllegalArgumentException("Product with SKU ${product.sku} already exists for this tenant")
        }
        
        val productToUpdate = product.copy(
            id = productId,
            tenantId = existingProduct.tenantId,
            createdAt = existingProduct.createdAt,
            updatedAt = java.time.LocalDateTime.now()
        )
        
        return productRepository.save(productToUpdate)
    }
    
    @Transactional
    @CacheEvict(value = ["products"], allEntries = true)
    fun deleteProduct(productId: UUID) {
        if (!productRepository.existsById(productId)) {
            throw ResourceNotFoundException("Product not found: $productId")
        }
        productRepository.deleteById(productId)
    }
    
    @Transactional
    @CacheEvict(value = ["products"], allEntries = true)
    fun toggleAvailability(productId: UUID): Product {
        val product = productRepository.findById(productId)
            .orElseThrow { ResourceNotFoundException("Product not found: $productId") }
        
        return productRepository.save(product.copy(
            isAvailable = !product.isAvailable,
            updatedAt = java.time.LocalDateTime.now()
        ))
    }
    
    @Transactional
    @CacheEvict(value = ["products"], allEntries = true)
    fun toggleFeatured(productId: UUID): Product {
        val product = productRepository.findById(productId)
            .orElseThrow { ResourceNotFoundException("Product not found: $productId") }
        
        return productRepository.save(product.copy(
            isFeatured = !product.isFeatured,
            updatedAt = java.time.LocalDateTime.now()
        ))
    }
    
    @Transactional
    @CacheEvict(value = ["products"], allEntries = true)
    fun updateStock(productId: UUID, quantity: Int): Product {
        val product = productRepository.findById(productId)
            .orElseThrow { ResourceNotFoundException("Product not found: $productId") }
        
        // Calculate new stock
        val newStock = product.stockQuantity + quantity
        
        return productRepository.save(product.copy(
            stockQuantity = newStock.coerceAtLeast(0),
            updatedAt = java.time.LocalDateTime.now()
        ))
    }
}
