package com.menuflow.repository.tenant

import com.menuflow.model.CampaignSend
import com.menuflow.model.SendStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Envios individuais de campanha (banco do TENANT). O dispatcher carrega os ids
 * QUEUED em uma transacao curta e processa um a um fora de transacao (para nao
 * segurar conexao durante os delays anti-ban).
 */
@Repository
interface CampaignSendRepository : JpaRepository<CampaignSend, UUID> {

    fun findByCampaignId(campaignId: UUID, pageable: Pageable): Page<CampaignSend>

    fun countByCampaignIdAndStatus(campaignId: UUID, status: SendStatus): Long

    /** Apenas os ids dos envios em fila — leve, para o loop de despacho. */
    @Query("SELECT s.id FROM CampaignSend s WHERE s.campaignId = :campaignId AND s.status = :status ORDER BY s.createdAt ASC")
    fun findIdsByCampaignIdAndStatus(
        @Param("campaignId") campaignId: UUID,
        @Param("status") status: SendStatus,
    ): List<UUID>

    /** Envios ainda em fila de um cliente (para marcar OPT_OUT quando ele revoga). */
    fun findByCustomerIdAndStatus(customerId: UUID, status: SendStatus): List<CampaignSend>
}
