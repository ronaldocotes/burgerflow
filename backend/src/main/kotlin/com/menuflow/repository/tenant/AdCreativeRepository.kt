package com.menuflow.repository.tenant

import com.menuflow.model.AdCreative
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Criativos de campanha (banco do TENANT). Sem filtro de escopo: db-per-tenant isola por
 * banco. O criativo e apagado por CASCADE quando a campanha e apagada (FK ON DELETE CASCADE).
 */
@Repository
interface AdCreativeRepository : JpaRepository<AdCreative, UUID> {

    fun findByCampaignId(campaignId: UUID): AdCreative?
}
