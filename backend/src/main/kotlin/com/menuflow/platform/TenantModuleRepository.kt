package com.menuflow.platform

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Repositório de overrides de módulo por tenant (banco de CONTROLE). Escopado
 * SEMPRE por tenantId — o painel super-admin age sobre qualquer tenant, mas nunca
 * mistura entitlements de tenants diferentes.
 */
@Repository
interface TenantModuleRepository : JpaRepository<TenantModule, UUID> {
    fun findByTenantId(tenantId: UUID): List<TenantModule>
    fun findByTenantIdAndModuleKey(tenantId: UUID, moduleKey: String): TenantModule?
}
