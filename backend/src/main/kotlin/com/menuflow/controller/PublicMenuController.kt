package com.menuflow.controller

import com.menuflow.dto.CategoryResponse
import com.menuflow.dto.ProductResponse
import com.menuflow.repository.control.TenantRepository
import com.menuflow.service.CategoryService
import com.menuflow.service.ProductService
import com.menuflow.tenant.TenantContext
import org.springframework.data.domain.Pageable
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class PublicMenuResponse(
    val categories: List<CategoryResponse>,
    val products: List<ProductResponse>,
)

@RestController
@RequestMapping("/public")
class PublicMenuController(
    private val tenantRepository: TenantRepository,
    private val categoryService: CategoryService,
    private val productService: ProductService,
) {
    @GetMapping("/{tenantSlug}/menu")
    fun getMenu(@PathVariable tenantSlug: String): ResponseEntity<PublicMenuResponse> {
        if (!tenantRepository.existsBySlug(tenantSlug)) {
            return ResponseEntity.notFound().build()
        }
        TenantContext.set(tenantSlug)
        return try {
            val categories = categoryService.list(Pageable.ofSize(100)).content
            val products = productService.list(Pageable.ofSize(500)).content
            ResponseEntity.ok(PublicMenuResponse(categories, products))
        } finally {
            TenantContext.clear()
        }
    }
}
