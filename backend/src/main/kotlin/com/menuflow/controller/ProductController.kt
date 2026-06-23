package com.menuflow.controller

import com.menuflow.dto.ProductCreateRequest
import com.menuflow.dto.ProductResponse
import com.menuflow.dto.ProductUpdateRequest
import com.menuflow.exception.BusinessException
import com.menuflow.service.IdempotencyService
import com.menuflow.service.ProductService
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/products")
class ProductController(
    private val productService: ProductService,
    private val idempotencyService: IdempotencyService,
) {

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','STAFF','CASHIER','KITCHEN')")
    fun list(@PageableDefault(size = 50, sort = ["displayOrder"]) pageable: Pageable): Page<ProductResponse> =
        productService.list(pageable)

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','STAFF','CASHIER','KITCHEN')")
    fun get(@PathVariable id: UUID): ProductResponse = productService.get(id)

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun create(
        @RequestHeader("Idempotency-Key") idempotencyKey: String?,
        @Valid @RequestBody req: ProductCreateRequest,
    ): ResponseEntity<ProductResponse> {
        if (idempotencyKey.isNullOrBlank()) {
            throw BusinessException("Idempotency-Key header is required")
        }
        val hash = idempotencyService.hash(req)
        idempotencyService.find(idempotencyKey, "products", hash)?.let { stored ->
            val cached = idempotencyService.deserialize(stored.body, ProductResponse::class.java)
            return ResponseEntity.status(stored.status).body(cached)
        }
        val created = productService.create(req)
        idempotencyService.save(idempotencyKey, "products", hash, HttpStatus.CREATED.value(), created)
        return ResponseEntity.created(URI.create("/api/v1/products/${created.id}")).body(created)
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun update(@PathVariable id: UUID, @Valid @RequestBody req: ProductUpdateRequest): ProductResponse =
        productService.update(id, req)

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun delete(@PathVariable id: UUID): ResponseEntity<Void> {
        productService.delete(id)
        return ResponseEntity.noContent().build()
    }
}
