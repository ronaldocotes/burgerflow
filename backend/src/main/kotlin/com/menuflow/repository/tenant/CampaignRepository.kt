package com.menuflow.repository.tenant

import com.menuflow.model.Campaign
import com.menuflow.model.CampaignStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * Campanhas do tenant (banco do TENANT). Sem filtro de escopo: db-per-tenant ja
 * isola por banco.
 */
@Repository
interface CampaignRepository : JpaRepository<Campaign, UUID> {
    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): Page<Campaign>

    /** Campanhas agendadas com horario ja vencido (varredura do CampaignSchedulerJob). */
    fun findByStatusAndScheduledAtLessThanEqual(status: CampaignStatus, cutoff: Instant): List<Campaign>

    /**
     * Transicao ATOMICA de status (guard no WHERE). Retorna 1 se ESTA chamada fez a
     * transicao e 0 se outro processo chegou antes (ou o estado ja mudou) — e isso
     * que garante que uma campanha agendada dispara UMA unica vez mesmo com o
     * scheduler e o start manual concorrendo. flushAutomatically: o bulk update nao
     * passa pelo contexto de persistencia; o flush evita perder writes pendentes da
     * mesma transacao (NAO usar clearAutomatically — descartaria INSERTs pendentes).
     */
    @Modifying(flushAutomatically = true)
    @Query(
        "update Campaign c set c.status = :to, c.updatedAt = :now " +
            "where c.id = :id and c.status = :from",
    )
    fun transitionStatus(
        @Param("id") id: UUID,
        @Param("from") from: CampaignStatus,
        @Param("to") to: CampaignStatus,
        @Param("now") now: Instant,
    ): Int
}
