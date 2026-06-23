package com.menuflow.controller

import com.menuflow.dto.ProductFlavorRequest
import com.menuflow.dto.ProductFlavorResponse
import com.menuflow.service.ProductFlavorService
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/products/{productId}/flavors")
class ProductFlavorController(private val service: ProductFlavorService) {

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER','KITCHEN','DELIVERY')")
    fun list(@PathVariable productId: UUID): List<ProductFlavorResponse> = service.list(productId)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun create(@PathVariable productId: UUID, @RequestBody req: ProductFlavorRequest): ProductFlavorResponse =
        service.create(productId, req)

    @PutMapping("/{flavorId}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun update(@PathVariable productId: UUID, @PathVariable flavorId: UUID, @RequestBody req: ProductFlavorRequest): ProductFlavorResponse =
        service.update(productId, flavorId, req)

    @DeleteMapping("/{flavorId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun delete(@PathVariable productId: UUID, @PathVariable flavorId: UUID) = service.delete(productId, flavorId)
}
