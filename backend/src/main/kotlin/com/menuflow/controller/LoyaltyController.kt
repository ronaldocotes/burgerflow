package com.menuflow.controller

import com.menuflow.dto.LoyaltyAdjustRequest
import com.menuflow.dto.LoyaltyStatusResponse
import com.menuflow.dto.LoyaltySummaryResponse
import com.menuflow.service.LoyaltyService
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

/**
 * Programa de Fidelidade (Fase 3.3). Sob o context-path /api/v1.
 *
 * Consulta e resgate são operação de balcão (ADMIN/MANAGER/CASHIER); o ajuste
 * manual de pontos é gestão (ADMIN/MANAGER) — alterar saldo é ato sensível.
 * db-per-tenant: a rota já aterrissa no banco do restaurante via TenantContext do
 * token assinado; o customerId é escopado ao banco do tenant.
 */
@RestController
@RequestMapping("/customers/{customerId}/loyalty")
class LoyaltyController(private val service: LoyaltyService) {

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    fun get(@PathVariable customerId: UUID): LoyaltyStatusResponse =
        service.getCustomerLoyalty(customerId)

    @PostMapping("/redeem/{rewardId}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @ResponseStatus(HttpStatus.OK)
    fun redeem(@PathVariable customerId: UUID, @PathVariable rewardId: UUID) =
        service.redeemReward(customerId, rewardId)

    @PostMapping("/adjust")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun adjust(
        @PathVariable customerId: UUID,
        @Valid @RequestBody req: LoyaltyAdjustRequest,
    ): LoyaltyStatusResponse =
        service.adjustPoints(customerId, req.delta, req.description)
}

/**
 * Sumário gerencial do programa de fidelidade — visão de tenant (não por cliente).
 * Separado de LoyaltyController porque o path não tem {customerId}.
 * ADMIN e MANAGER: dados para o painel de gestão.
 */
@RestController
@RequestMapping("/loyalty")
class LoyaltySummaryController(private val service: LoyaltyService) {

    /**
     * GET /loyalty/summary?from=2026-06-01&to=2026-06-30
     *
     * Retorna métricas do programa de fidelidade no período (datas ISO). O TenantContext
     * já foi vinculado pelo JwtAuthFilter antes desta chamada.
     */
    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun summary(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): LoyaltySummaryResponse = service.getSummary(from, to)
}
