package com.menuflow.platform

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Painel super-admin — gestão de tenants. Rotas sob /admin/tenants (context-path
 * /api/v1). Gate DUPLO: path-level em SecurityConfig no prefixo admin + @PreAuthorize
 * aqui (suspensório). Toda mutação é auditada em platform_audit_log.
 *
 * IDOR cross-tenant N/A por design: o super-admin age sobre QUALQUER tenant; o slug
 * vem no path e é sempre resolvido contra a tabela tenants (404 se não existe). A
 * defesa é o papel SUPER_ADMIN nas duas camadas, não o escopo do token.
 */
@RestController
@RequestMapping("/admin/tenants")
@PreAuthorize("hasRole('SUPER_ADMIN')")
class PlatformTenantController(
    private val provisioningService: TenantProvisioningService,
    private val tenantUsageService: UsageSnapshotService,
) {

    @GetMapping
    fun list(): List<TenantSummaryResponse> = provisioningService.listTenants()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody req: CreateTenantRequest): TenantCreatedResponse =
        provisioningService.provision(req)

    @PatchMapping("/{slug}")
    fun update(
        @PathVariable slug: String,
        @Valid @RequestBody req: UpdateTenantRequest,
    ): TenantSummaryResponse = provisioningService.updateTenant(slug, req)

    /**
     * Métricas de uso do tenant: pedidos no mês, tamanho do banco, último login.
     * Dados do snapshot diário gerado às 03:00 (UTC) pelo [UsageSnapshotScheduler].
     * Se ainda não há snapshot (tenant recém-provisionado), dispara um síncrono.
     */
    @GetMapping("/{slug}/usage")
    fun usage(@PathVariable slug: String): TenantUsageResponse =
        tenantUsageService.getUsage(slug)
}
