package com.menuflow.controller

import com.menuflow.dto.CampaignCreateRequest
import com.menuflow.dto.CampaignResponse
import com.menuflow.dto.CampaignSendResponse
import com.menuflow.security.SecurityUtils
import com.menuflow.service.CampaignService
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Campanhas de marketing por WhatsApp (Fase 3.4). Sob o context-path /api/v1 (logo
 * @RequestMapping = /campaigns). Restrito a ADMIN/MANAGER (gestao de marketing). O
 * disparo roda em segundo plano (CampaignDispatcher) com delay anti-ban; o endpoint
 * retorna imediatamente. db-per-tenant: a rota ja aterrissa no banco do restaurante.
 */
@RestController
@RequestMapping("/campaigns")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
class CampaignController(private val service: CampaignService) {

    @GetMapping
    fun list(
        @PageableDefault(size = 20, sort = ["createdAt"]) pageable: Pageable,
    ): Page<CampaignResponse> = service.list(pageable)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody req: CampaignCreateRequest): CampaignResponse =
        service.create(req)

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): CampaignResponse = service.get(id)

    @PostMapping("/{id}/start")
    fun start(@PathVariable id: UUID): CampaignResponse {
        // tenantSlug do principal assinado: o dispatcher async perde o TenantContext
        // do thread HTTP, entao precisa do slug para rotear de volta ao banco certo.
        val slug = SecurityUtils.currentPrincipalOrThrow().tenantSlug
        return service.start(id, slug)
    }

    @PostMapping("/{id}/pause")
    fun pause(@PathVariable id: UUID): CampaignResponse = service.pause(id)

    @GetMapping("/{id}/sends")
    fun sends(
        @PathVariable id: UUID,
        @PageableDefault(size = 50, sort = ["createdAt"]) pageable: Pageable,
    ): Page<CampaignSendResponse> = service.listSends(id, pageable)
}
