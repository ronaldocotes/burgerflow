package com.menuflow.controller

import com.menuflow.ads.AdMetricsResponse
import com.menuflow.ads.AdMetricsService
import com.menuflow.platform.ModuleKey
import com.menuflow.platform.RequiresModule
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Dashboard de metricas de anuncio (Fase 8.1). Mesmo gate triplo do AdAccountController:
 *   1. @PreAuthorize a nivel de classe: so ADMIN/MANAGER (gestao/verba);
 *   2. @RequiresModule(ADS): modulo habilitado para o tenant (entitlement de controle);
 *   3. db-per-tenant: a rota ja aterrissa no banco do restaurante (isolamento).
 *
 * Nesta fase as metricas sao a nivel de CONTA (agregam todas as campanhas que o
 * restaurante ja roda no Meta Ads Manager). Granularidade por campanha fica para a 8.2.
 */
@RestController
@RequestMapping("/ads")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
class AdMetricsController(private val service: AdMetricsService) {

    /** Serie diaria dos ultimos [days] dias (default 30) de UMA conta, para o grafico. */
    @GetMapping("/accounts/{id}/metrics")
    @RequiresModule(ModuleKey.ADS)
    fun metrics(
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "30") days: Int,
    ): List<AdMetricsResponse> = service.list(id, days)
}
