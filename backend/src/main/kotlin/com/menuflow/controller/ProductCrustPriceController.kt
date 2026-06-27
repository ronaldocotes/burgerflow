package com.menuflow.controller

import com.menuflow.dto.ProductCrustPriceRequest
import com.menuflow.dto.ProductCrustPriceResponse
import com.menuflow.service.ProductCrustPriceService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/products/{productId}/crust-prices")
class ProductCrustPriceController(private val service: ProductCrustPriceService) {

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER','KITCHEN','DELIVERY')")
    fun list(@PathVariable productId: UUID): List<ProductCrustPriceResponse> = service.list(productId)

    /** Upsert: cria/atualiza o preço de uma borda (chave natural produto+borda). */
    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun upsert(@PathVariable productId: UUID, @Valid @RequestBody req: ProductCrustPriceRequest): ProductCrustPriceResponse =
        service.upsert(productId, req)

    @DeleteMapping("/{crustType}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun delete(@PathVariable productId: UUID, @PathVariable crustType: String) = service.delete(productId, crustType)
}
