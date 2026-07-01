package com.menuflow.repository.control

import com.menuflow.model.control.OpenDeliveryIntegrationStatus
import com.menuflow.model.control.OpenDeliveryPlatform
import com.menuflow.model.control.OpenDeliveryTenantConfig
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/** Repositorio (banco de CONTROLE) do vinculo tenant<->plataforma Open Delivery. */
@Repository
interface OpenDeliveryTenantConfigRepository : JpaRepository<OpenDeliveryTenantConfig, UUID> {
    fun findByStatus(status: OpenDeliveryIntegrationStatus): List<OpenDeliveryTenantConfig>
    fun findByCompanyId(companyId: UUID): OpenDeliveryTenantConfig?
    fun findByPlatform(platform: OpenDeliveryPlatform): List<OpenDeliveryTenantConfig>
}
