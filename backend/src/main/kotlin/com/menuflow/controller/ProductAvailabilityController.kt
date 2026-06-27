package com.menuflow.controller

import com.menuflow.dto.AvailabilityRequest
import com.menuflow.dto.AvailabilityResponse
import com.menuflow.service.ProductAvailabilityService
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

/** Disponibilidade do produto por canal/horário. context-path /api/v1 já aplicado. */
@RestController
@RequestMapping("/products/{productId}/availability")
class ProductAvailabilityController(private val service: ProductAvailabilityService) {

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER','KITCHEN','DELIVERY')")
    fun get(@PathVariable productId: UUID): AvailabilityResponse = service.get(productId)

    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun set(@PathVariable productId: UUID, @Valid @RequestBody req: AvailabilityRequest): AvailabilityResponse =
        service.set(productId, req)

    @GetMapping("/now")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER','KITCHEN','DELIVERY')")
    fun now(
        @PathVariable productId: UUID,
        @RequestParam(required = false) channel: String?,
    ): Map<String, Boolean> = mapOf("available" to service.isAvailableNow(productId, channel))
}
