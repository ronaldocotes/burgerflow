package com.menuflow.controller

import com.menuflow.ads.AdCampaignResponse
import com.menuflow.ads.AdCampaignService
import com.menuflow.ads.CreateAdCampaignRequest
import com.menuflow.platform.ModuleKey
import com.menuflow.platform.RequiresModule
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Criar / pausar / ativar campanha de Meta Ads (Fase 8.2). Esta e a fase que GASTA DINHEIRO
 * REAL. Mesmo gate triplo do modulo:
 *   1. @PreAuthorize a nivel de classe: so ADMIN/MANAGER (gestao de verba);
 *   2. @RequiresModule(ADS): modulo habilitado para o tenant (entitlement de controle);
 *   3. db-per-tenant: a rota ja aterrissa no banco do restaurante (isolamento).
 *
 * Idempotencia da criacao pelo header Idempotency-Key (a UNIQUE por conta+chave impede
 * campanha duplicada em retry/double-click). A campanha NASCE PAUSED; ativar e endpoint
 * separado e auditado que REVALIDA o teto de verba.
 */
@RestController
@RequestMapping("/ads/campaigns")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
class AdCampaignController(private val service: AdCampaignService) {

    /** Cria a campanha (nasce PAUSED). Idempotency-Key no header. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresModule(ModuleKey.ADS)
    fun create(
        @Valid @RequestBody req: CreateAdCampaignRequest,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
    ): AdCampaignResponse = service.create(req, idempotencyKey ?: "")

    /** Pausa a campanha. */
    @PostMapping("/{id}/pause")
    @RequiresModule(ModuleKey.ADS)
    fun pause(@PathVariable id: UUID): AdCampaignResponse = service.pause(id)

    /** Ativa a campanha (revalida o teto de verba). */
    @PostMapping("/{id}/activate")
    @RequiresModule(ModuleKey.ADS)
    fun activate(@PathVariable id: UUID): AdCampaignResponse = service.activate(id)

    /** Lista as campanhas do tenant. */
    @GetMapping
    @RequiresModule(ModuleKey.ADS)
    fun list(): List<AdCampaignResponse> = service.list()

    /** Detalhe de uma campanha do tenant. */
    @GetMapping("/{id}")
    @RequiresModule(ModuleKey.ADS)
    fun get(@PathVariable id: UUID): AdCampaignResponse = service.get(id)
}
