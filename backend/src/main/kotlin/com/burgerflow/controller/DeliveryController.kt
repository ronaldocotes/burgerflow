package com.burgerflow.controller

import com.burgerflow.dto.AssignDriverRequest
import com.burgerflow.dto.DeliveryOrderResponse
import com.burgerflow.dto.DeliveryStatusUpdateRequest
import com.burgerflow.dto.DriverCreateRequest
import com.burgerflow.dto.DriverResponse
import com.burgerflow.service.DeliveryService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.net.URI
import java.util.UUID

/**
 * Delivery dispatch endpoints (Sprint 2). RBAC: DRIVER, OPERATOR or ADMIN.
 * Driver management (create) is restricted to OPERATOR/ADMIN; a DRIVER can read
 * the active queue and update dispatch status.
 */
@RestController
@RequestMapping("/delivery")
@PreAuthorize("hasAnyRole('DRIVER','OPERATOR','ADMIN')")
class DeliveryController(
    private val deliveryService: DeliveryService,
) {

    @PostMapping("/drivers")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    fun createDriver(@Valid @RequestBody req: DriverCreateRequest): ResponseEntity<DriverResponse> {
        val created = deliveryService.createDriver(req)
        return ResponseEntity
            .created(URI.create("/api/v1/delivery/drivers/${created.id}"))
            .body(created)
    }

    @GetMapping("/drivers")
    fun listDrivers(): List<DriverResponse> = deliveryService.listActiveDrivers()

    @PostMapping("/orders/{orderId}/assign")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    fun assign(
        @PathVariable orderId: UUID,
        @Valid @RequestBody req: AssignDriverRequest,
    ): DeliveryOrderResponse = deliveryService.assign(orderId, req)

    @PutMapping("/orders/{orderId}/status")
    fun updateStatus(
        @PathVariable orderId: UUID,
        @Valid @RequestBody req: DeliveryStatusUpdateRequest,
    ): DeliveryOrderResponse = deliveryService.updateStatus(orderId, req)

    @GetMapping("/orders/active")
    fun active(): List<DeliveryOrderResponse> = deliveryService.activeDeliveryOrders()
}
