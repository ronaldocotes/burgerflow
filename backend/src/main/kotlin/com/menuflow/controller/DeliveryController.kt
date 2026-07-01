package com.menuflow.controller

import com.menuflow.dto.AssignDriverRequest
import com.menuflow.dto.DeliveryOfferResponse
import com.menuflow.dto.DeliveryOrderResponse
import com.menuflow.dto.DeliveryStatusUpdateRequest
import com.menuflow.dto.DriverCreateRequest
import com.menuflow.dto.DriverResponse
import com.menuflow.dto.LocationUpdateRequest
import com.menuflow.dto.ShiftRequest
import com.menuflow.service.DeliveryService
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

    // --- Fase 6.1: app do motoboy (turno, GPS, ofertas, meus pedidos) ---

    /**
     * Liga/desliga o turno de um entregador. Um gestor mexe em qualquer motoboy; um
     * DRIVER so no proprio (validado no servico pelo elo user_id). MANAGER entra aqui
     * (nao esta no RBAC de classe) por ser quem gerencia a escala da frota.
     */
    @PostMapping("/drivers/{id}/shift")
    @PreAuthorize("hasAnyRole('DRIVER','MANAGER','OPERATOR','ADMIN')")
    fun setShift(
        @PathVariable id: UUID,
        @Valid @RequestBody req: ShiftRequest,
    ): DriverResponse = deliveryService.setShift(id, req.activeShift)

    /** O motoboy envia sua posicao (GPS). Resolve o entregador pelo user do token. */
    @PostMapping("/location")
    fun updateLocation(@Valid @RequestBody req: LocationUpdateRequest): DriverResponse =
        deliveryService.updateLocation(req)

    /** O motoboy aceita uma oferta (so o dono da oferta). */
    @PostMapping("/offers/{id}/accept")
    fun acceptOffer(@PathVariable id: UUID): DeliveryOfferResponse = deliveryService.acceptOffer(id)

    /** O motoboy recusa uma oferta (so o dono da oferta). */
    @PostMapping("/offers/{id}/reject")
    fun rejectOffer(@PathVariable id: UUID): DeliveryOfferResponse = deliveryService.rejectOffer(id)

    /** Pedidos de entrega ativos atribuidos ao motoboy logado. */
    @GetMapping("/orders/my")
    fun myOrders(): List<DeliveryOrderResponse> = deliveryService.myOrders()
}
