package com.menuflow.ifood

import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Saude da integracao iFood por tenant (admin).
 *
 * GET /api/v1/admin/ifood/health (context-path /api/v1 + /admin/ifood).
 *
 * ATENCAO CROSS-TENANT: este endpoint le ifood_tenant_config do banco de CONTROLE
 * e devolve o estado de TODOS os merchants (companyId/status/falhas). A tarefa
 * pediu gate ADMIN; porem ADMIN e um papel de TENANT, entao um admin de um
 * restaurante enxergaria o estado operacional dos demais. Para o codebase, a
 * convencao fail-closed de visao cross-tenant e SUPER_ADMIN (ver
 * TenantMigrationAdminController). Mantido ADMIN conforme pedido nesta fase de
 * fundacao (sem dados reais ainda) — REVISAR com o dono/Centuriao antes de expor
 * dados reais: ou trocar para SUPER_ADMIN, ou filtrar pela company do chamador.
 */
@RestController
@RequestMapping("/admin/ifood")
class IfoodHealthController(
    private val healthService: IfoodHealthService,
) {
    @GetMapping("/health")
    @PreAuthorize("hasRole('ADMIN')")
    fun health(): List<IfoodTenantHealthView> = healthService.tenantHealth()
}
