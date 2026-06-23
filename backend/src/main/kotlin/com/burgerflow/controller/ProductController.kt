package com.burgerflow.controller

import com.burgerflow.model.Product
import com.burgerflow.repository.ProductRepository
import com.burgerflow.service.ProductService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springdoc.api.annotations.ParameterObject
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "Products", description = "Operations related to products")
class ProductController(private val productService: ProductService) {
    
    @PostMapping
    @Operation(summary = "Create a new product")
    fun createProduct(@Valid @RequestBody request: Product): ResponseEntity<Product> {
        val product = productService.createProduct(request)
        return ResponseEntity.status(HttpStatus.CREATED).body(product)
    }
    
    @GetMapping("/{productId}")
    @Operation(summary = "Get product by ID")
    fun getProductById(@PathVariable productId: UUID): ResponseEntity<Product> {
        val product = productService.getProductById(productId)
        return ResponseEntity.ok(product)
    }
    
    @GetMapping("/tenant/{tenantId}")
    @Operation(summary = "Get all products for a tenant")
    fun getProductsByTenant(
        @PathVariable tenantId: UUID,
        @RequestParam(required = false, defaultValue = "true") includeInactive: Boolean = false,
        @ParameterObject @PageableDefault(size = 50, sort = ["displayOrder,asc"]) pageable: Pageable
    ): ResponseEntity<Page<Product>> {
        val products = productService.getProductsByTenant(tenantId, includeInactive, pageable)
        return ResponseEntity.ok(products)
    }
    
    @GetMapping("/tenant/{tenantId}/available")
    @Operation(summary = "Get available products for a tenant")
    fun getAvailableProductsByTenant(
        @PathVariable tenantId: UUID,
        @ParameterObject @PageableDefault(size = 50, sort = ["displayOrder,asc"]) pageable: Pageable
    ): ResponseEntity<Page<Product>> {
        val products = productService.getAvailableProductsByTenant(tenantId, pageable)
        return ResponseEntity.ok(products)
    }
    
    @GetMapping("/tenant/{tenantId}/category/{categoryId}")
    @Operation(summary = "Get products by category")
    fun getProductsByCategory(
        @PathVariable tenantId: UUID,
        @PathVariable categoryId: UUID,
        @ParameterObject @PageableDefault(size = 50, sort = ["displayOrder,asc"]) pageable: Pageable
    ): ResponseEntity<Page<Product>> {
        val products = productService.getProductsByCategory(tenantId, categoryId, pageable)
        return ResponseEntity.ok(products)
    }
    
    @GetMapping("/tenant/{tenantId}/featured")
    @Operation(summary = "Get featured products for a tenant")
    fun getFeaturedProductsByTenant(
        @PathVariable tenantId: UUID,
        @ParameterObject @PageableDefault(size = 20, sort = ["displayOrder,asc"]) pageable: Pageable
    ): ResponseEntity<Page<Product>> {
        val products = productService.getFeaturedProductsByTenant(tenantId, pageable)
        return ResponseEntity.ok(products)
    }
    
    @GetMapping("/tenant/{tenantId}/low-stock")
    @Operation(summary = "Get low stock products for a tenant")
    fun getLowStockProducts(
        @PathVariable tenantId: UUID,
        @ParameterObject @PageableDefault(size = 50, sort = ["stockQuantity,asc"]) pageable: Pageable
    ): ResponseEntity<Page<Product>> {
        val products = productService.getLowStockProducts(tenantId, pageable)
        return ResponseEntity.ok(products)
    }
    
    @GetMapping("/tenant/{tenantId}/search")
    @Operation(summary = "Search products by name, description or SKU")
    fun searchProducts(
        @PathVariable tenantId: UUID,
        @RequestParam query: String,
        @ParameterObject @PageableDefault(size = 50) pageable: Pageable
    ): ResponseEntity<Page<Product>> {
        val products = productService.searchProducts(tenantId, query, pageable)
        return ResponseEntity.ok(products)
    }
    
    @PutMapping("/{productId}")
    @Operation(summary = "Update a product")
    fun updateProduct(
        @PathVariable productId: UUID,
        @Valid @RequestBody request: Product
    ): ResponseEntity<Product> {
        val product = productService.updateProduct(productId, request)
        return ResponseEntity.ok(product)
    }
    
    @DeleteMapping("/{productId}")
    @Operation(summary = "Delete a product")
    fun deleteProduct(@PathVariable productId: UUID): ResponseEntity<Void> {
        productService.deleteProduct(productId)
        return ResponseEntity.noContent().build()
    }
    
    @PatchMapping("/{productId}/toggle-availability")
    @Operation(summary = "Toggle product availability")
    fun toggleAvailability(@PathVariable productId: UUID): ResponseEntity<Product> {
        val product = productService.toggleAvailability(productId)
        return ResponseEntity.ok(product)
    }
    
    @PatchMapping("/{productId}/toggle-featured")
    @Operation(summary = "Toggle product featured status")
    fun toggleFeatured(@PathVariable productId: UUID): ResponseEntity<Product> {
        val product = productService.toggleFeatured(productId)
        return ResponseEntity.ok(product)
    }
    
    @PostMapping("/{productId}/stock")
    @Operation(summary = "Update product stock")
    fun updateStock(
        @PathVariable productId: UUID,
        @RequestParam quantity: Int
    ): ResponseEntity<Product> {
        val product = productService.updateStock(productId, quantity)
        return ResponseEntity.ok(product)
    }
}
