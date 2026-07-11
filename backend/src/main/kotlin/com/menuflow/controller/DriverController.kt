package com.menuflow.controller

import com.menuflow.dto.CloseSettlementRequest
import com.menuflow.dto.DeliveryDriverResponse
import com.menuflow.dto.DriverConfigRequest
import com.menuflow.dto.DriverConfigResponse
import com.menuflow.dto.DriverSettlementResponse
import com.menuflow.dto.OpenSettlementRequest
import com.menuflow.model.DriverSettlementStatus
import com.menuflow.security.SecurityUtils
import com.menuflow.service.DriverService
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Acerto financeiro de entregadores. Sob o context-path /api/v1 (logo
 * @RequestMapping = /drivers). Tudo restrito a ADMIN/MANAGER (gestao financeira).
 * O ator (quem faz upsert/abre/fecha) vem do principal assinado, nao do corpo.
 *
 * {driverId} aqui e o id do DeliveryDriver (delivery_drivers.id = orders.driver_id).
 */
@RestController
@RequestMapping("/drivers")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
class DriverController(private val service: DriverService) {

    // --- Entregadores ---

    /**
     * Lista os entregadores do tenant (ativos e inativos) com o id correto para
     * montar /drivers/{driverId}/config e os acertos. Substitui o GET /users?role=DELIVERY.
     */
    @GetMapping
    fun listDrivers(): List<DeliveryDriverResponse> = service.listDrivers()

    // --- Configuracao de remuneracao ---

    @GetMapping("/{driverId}/config")
    fun getConfig(@PathVariable driverId: UUID): DriverConfigResponse =
        service.getConfig(driverId)

    // D-B: configurar os valores/tarifas de remuneracao e so ADMIN (mais restrito que
    // a classe ADMIN/MANAGER). MANAGER abre/fecha acertos, mas nao mexe nas tarifas.
    @PutMapping("/{driverId}/config")
    @PreAuthorize("hasRole('ADMIN')")
    fun upsertConfig(
        @PathVariable driverId: UUID,
        @Valid @RequestBody req: DriverConfigRequest,
    ): DriverConfigResponse = service.upsertConfig(driverId, actorId(), req)

    // --- Acertos ---

    @GetMapping("/settlements")
    fun listSettlements(
        @RequestParam(required = false) driverId: UUID?,
        @RequestParam(required = false) status: DriverSettlementStatus?,
        @PageableDefault(size = 20, sort = ["createdAt"]) pageable: Pageable,
    ): Page<DriverSettlementResponse> = service.list(driverId, status, pageable)

    @PostMapping("/settlements/open")
    @ResponseStatus(HttpStatus.CREATED)
    fun openSettlement(@Valid @RequestBody req: OpenSettlementRequest): DriverSettlementResponse =
        service.openSettlement(actorId(), req)

    @GetMapping("/settlements/{id}")
    fun getSettlement(@PathVariable id: UUID): DriverSettlementResponse =
        service.get(id)

    @PostMapping("/settlements/{id}/close")
    fun closeSettlement(
        @PathVariable id: UUID,
        @Valid @RequestBody req: CloseSettlementRequest,
    ): DriverSettlementResponse = service.closeSettlement(id, actorId(), req)

    private fun actorId(): UUID = SecurityUtils.currentPrincipalOrThrow().userId
}
