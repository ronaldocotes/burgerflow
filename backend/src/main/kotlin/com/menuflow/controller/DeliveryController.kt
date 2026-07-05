package com.menuflow.controller

import com.menuflow.dto.AssignDriverRequest
import com.menuflow.dto.DeliveryOfferResponse
import com.menuflow.dto.DeliveryOrderResponse
import com.menuflow.dto.DeliveryStatusUpdateRequest
import com.menuflow.dto.DriverCreateRequest
import com.menuflow.dto.DriverEarningsResponse
import com.menuflow.dto.DriverMeResponse
import com.menuflow.dto.DriverResponse
import com.menuflow.dto.DriverUserLinkRequest
import com.menuflow.dto.LocationUpdateRequest
import com.menuflow.dto.ShiftRequest
import com.menuflow.service.DeliveryService
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.net.URI
import java.time.LocalDate
import java.util.UUID

/**
 * Delivery dispatch endpoints (Sprint 2). RBAC: DRIVER, OPERATOR or ADMIN.
 * Driver management (create) is restricted to OPERATOR/ADMIN; a DRIVER can read
 * the active queue and update dispatch status.
 */
@RestController
@RequestMapping("/delivery")
@PreAuthorize("hasAnyRole('DRIVER','OPERATOR','MANAGER','ADMIN')")
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

    /**
     * Frota completa com GPS/bateria de todos os motoboys — dados de gestao
     * (auditoria M1): DRIVER fica de fora; o app usa GET /delivery/me.
     */
    @GetMapping("/drivers")
    @PreAuthorize("hasAnyRole('OPERATOR','MANAGER','ADMIN')")
    fun listDrivers(): List<DriverResponse> = deliveryService.listActiveDrivers()

    @PostMapping("/orders/{orderId}/assign")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    fun assign(
        @PathVariable orderId: UUID,
        @Valid @RequestBody req: AssignDriverRequest,
    ): DeliveryOrderResponse = deliveryService.assign(orderId, req)

    /**
     * Avanca o status do despacho. PUT e o contrato legado do painel; POST e o
     * espelho para o app do motoboy (Fase 6.2) — mesma semantica, mesma FSM. O
     * servico amarra o DONO (DRIVER so avanca a propria entrega) e trata retry
     * idempotente (repetir o mesmo status alvo = no-op).
     */
    @RequestMapping(
        "/orders/{orderId}/status",
        method = [RequestMethod.PUT, RequestMethod.POST],
    )
    fun updateStatus(
        @PathVariable orderId: UUID,
        @Valid @RequestBody req: DeliveryStatusUpdateRequest,
    ): DeliveryOrderResponse = deliveryService.updateStatus(orderId, req)

    /**
     * Fila de entregas ativas do TENANT inteiro, com endereco/telefone dos clientes
     * — dados de gestao (auditoria M1): DRIVER fica de fora; o app usa /orders/my,
     * que so devolve os pedidos do proprio motoboy.
     */
    @GetMapping("/orders/active")
    @PreAuthorize("hasAnyRole('OPERATOR','MANAGER','ADMIN')")
    fun active(): List<DeliveryOrderResponse> = deliveryService.activeDeliveryOrders()

    // --- Fase 6.1: app do motoboy (turno, GPS, ofertas, meus pedidos) ---

    /**
     * Liga/desliga o turno de um entregador. Um gestor mexe em qualquer motoboy; um
     * DRIVER so no proprio (validado no servico pelo elo user_id). MANAGER entra por
     * ser quem gerencia a escala da frota. O app do motoboy usa POST /delivery/shift.
     */
    @PostMapping("/drivers/{id}/shift")
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

    // --- Fase 6.2: perfil, turno proprio, ofertas pendentes, ganhos e vinculo ---

    /** Perfil do motoboy logado: dados, turno e config de remuneracao (leitura). */
    @GetMapping("/me")
    fun me(): DriverMeResponse = deliveryService.me()

    /** Liga/desliga o turno do PROPRIO motoboy logado (app; sem id na rota). */
    @PostMapping("/shift")
    fun setOwnShift(@Valid @RequestBody req: ShiftRequest): DriverResponse =
        deliveryService.setOwnShift(req.activeShift)

    /** Ofertas pendentes (OFFERED, nao expiradas) do motoboy logado. */
    @GetMapping("/offers/my")
    fun myOffers(): List<DeliveryOfferResponse> = deliveryService.myOffers()

    /**
     * Ganhos do motoboy logado no periodo [from, to] (ISO yyyy-MM-dd; default hoje).
     * Sem driverId na rota/query: o motoboy e SEMPRE o do token assinado.
     */
    @GetMapping("/earnings/my")
    fun myEarnings(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): DriverEarningsResponse = deliveryService.myEarnings(from, to)

    /**
     * Vincula/desvincula (userId nulo) o entregador a um usuario DRIVER do banco de
     * controle — o elo que permite o login do app resolver o motoboy. So gestao.
     */
    @PutMapping("/drivers/{id}/user")
    @PreAuthorize("hasAnyRole('OPERATOR','MANAGER','ADMIN')")
    fun linkDriverUser(
        @PathVariable id: UUID,
        @RequestBody req: DriverUserLinkRequest,
    ): DriverResponse = deliveryService.linkDriverUser(id, req.userId)
}
