package com.menuflow.repository.control

import com.menuflow.model.control.IfoodIntegrationStatus
import com.menuflow.model.control.IfoodTenantConfig
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/** Repositorio (banco de CONTROLE) do vinculo tenant<->merchant iFood. */
@Repository
interface IfoodTenantConfigRepository : JpaRepository<IfoodTenantConfig, UUID> {
    fun findByStatus(status: IfoodIntegrationStatus): List<IfoodTenantConfig>
    fun findByCompanyId(companyId: UUID): IfoodTenantConfig?
    fun findByMerchantId(merchantId: String): IfoodTenantConfig?
}
