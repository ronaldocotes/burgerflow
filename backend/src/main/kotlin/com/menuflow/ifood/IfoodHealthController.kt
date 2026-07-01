package com.menuflow.ifood

import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Saude da integracao iFood (visao GLOBAL da plataforma).
 *
 * GET /api/v1/admin/ifood/health (context-path /api/v1 + /admin/ifood).
 *
 * CROSS-TENANT: este endpoint le ifood_tenant_config do banco de CONTROLE e
 * devolve o estado de TODOS os merchants (companyId/status/falhas). Por isso e
 * gated em SUPER_ADMIN (papel de plataforma) — NAO ADMIN, que e papel de tenant
 * e vazaria o estado operacional de outros restaurantes (IDOR cross-tenant).
 * Segue a convencao fail-closed de visao cross-tenant do codebase
 * (ver TenantMigrationAdminController). Se um dia for preciso um health por
 * tenant para ADMIN, criar rota separada filtrando pela companyId do chamador.
 */
@RestController
@RequestMapping("/admin/ifood")
class IfoodHealthController(
    private val healthService: IfoodHealthService,
) {
    @GetMapping("/health")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    fun health(): List<IfoodTenantHealthView> = healthService.tenantHealth()
}
