package com.menuflow.controller

import com.menuflow.dto.ProductSizeRequest
import com.menuflow.dto.ProductSizeResponse
import com.menuflow.service.ProductSizeService
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/products/{productId}/sizes")
class ProductSizeController(private val service: ProductSizeService) {

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER','KITCHEN','DELIVERY')")
    fun list(@PathVariable productId: UUID): List<ProductSizeResponse> = service.list(productId)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun create(@PathVariable productId: UUID, @RequestBody req: ProductSizeRequest): ProductSizeResponse =
        service.create(productId, req)

    @PutMapping("/{sizeId}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun update(@PathVariable productId: UUID, @PathVariable sizeId: UUID, @RequestBody req: ProductSizeRequest): ProductSizeResponse =
        service.update(productId, sizeId, req)

    @DeleteMapping("/{sizeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun delete(@PathVariable productId: UUID, @PathVariable sizeId: UUID) = service.delete(productId, sizeId)
}
