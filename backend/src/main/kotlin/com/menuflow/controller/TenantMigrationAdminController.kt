package com.menuflow.controller

import com.menuflow.tenant.TenantMigrationStatusService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Platform admin: migration drift-check across ALL tenants.
 *
 * This reads the cross-tenant control ledger (every hamburgueria's migration
 * state), so it is a PLATFORM operation, not a tenant-scoped one. It is gated to
 * the SUPER_ADMIN role on purpose: no ordinary tenant ADMIN should see other
 * tenants' state. Fail-closed — until the auth layer (Craudio) actually issues a
 * SUPER_ADMIN role in the JWT, this endpoint is reachable by NOBODY, which is the
 * safe default for a cross-tenant view. See conhecimento Seç.5 (IDOR cross-tenant).
 */
@RestController
@RequestMapping("/admin/tenants")
class TenantMigrationAdminController(
    private val statusService: TenantMigrationStatusService,
) {

    @GetMapping("/migration-status")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    fun migrationStatus(): MigrationStatusResponse {
        val latest = statusService.latestAvailableVersion()
        val tenants = statusService.statusForAllTenants()
        return MigrationStatusResponse(
            latestAvailableVersion = latest,
            tenantsWithDrift = tenants.count { it.drift },
            tenants = tenants.map {
                TenantMigrationStatusDto(
                    tenantSlug = it.tenantSlug,
                    appliedVersion = it.appliedVersion,
                    latestVersion = it.latestVersion,
                    drift = it.drift,
                    lastAppliedAt = it.lastAppliedAt,
                    lastSuccess = it.lastSuccess,
                )
            },
        )
    }

    data class MigrationStatusResponse(
        val latestAvailableVersion: String,
        val tenantsWithDrift: Int,
        val tenants: List<TenantMigrationStatusDto>,
    )

    data class TenantMigrationStatusDto(
        val tenantSlug: String,
        val appliedVersion: String?,
        val latestVersion: String,
        val drift: Boolean,
        val lastAppliedAt: String?,
        val lastSuccess: Boolean,
    )
}
