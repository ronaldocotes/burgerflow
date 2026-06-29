package com.menuflow.repository.tenant

import com.menuflow.model.Campaign
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Campanhas do tenant (banco do TENANT). Sem filtro de escopo: db-per-tenant ja
 * isola por banco.
 */
@Repository
interface CampaignRepository : JpaRepository<Campaign, UUID> {
    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): Page<Campaign>
}
