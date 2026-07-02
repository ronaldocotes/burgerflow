package com.menuflow.controller

import com.menuflow.dto.CouponCreateRequest
import com.menuflow.dto.CouponRedemptionResponse
import com.menuflow.dto.CouponResponse
import com.menuflow.dto.CouponSummaryResponse
import com.menuflow.dto.CouponUpdateRequest
import com.menuflow.security.SecurityUtils
import com.menuflow.service.CouponService
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.util.UUID

/**
 * CRUD de cupons (Fase 3.2). Sob o context-path /api/v1 (logo @RequestMapping =
 * /coupons). Restrito a ADMIN/MANAGER (gestão de promoções). O ator de cada mutação
 * vem do principal assinado, não do corpo.
 */
@RestController
@RequestMapping("/coupons")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
class CouponController(private val service: CouponService) {

    @GetMapping
    fun list(
        @RequestParam(required = false) active: Boolean?,
        @PageableDefault(size = 20, sort = ["createdAt"]) pageable: Pageable,
    ): Page<CouponResponse> = service.list(active, pageable)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody req: CouponCreateRequest): CouponResponse =
        service.create(req, actorId())

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody req: CouponUpdateRequest,
    ): CouponResponse = service.update(id, req, actorId())

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deactivate(@PathVariable id: UUID) = service.deactivate(id, actorId())

    @GetMapping("/{id}/redemptions")
    fun redemptions(
        @PathVariable id: UUID,
        @PageableDefault(size = 20, sort = ["redeemedAt"]) pageable: Pageable,
    ): Page<CouponRedemptionResponse> = service.listRedemptions(id, pageable)

    /**
     * Sumário de performance dos cupons num período.
     * GET /coupons/summary?from=2026-01-01&to=2026-01-31
     * Retorna totais de redenções e desconto + top-5 cupons por uso.
     */
    @GetMapping("/summary")
    fun summary(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): CouponSummaryResponse = service.summary(from, to)

    private fun actorId(): UUID = SecurityUtils.currentPrincipalOrThrow().userId
}
