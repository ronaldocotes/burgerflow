package com.menuflow.repository.tenant

import com.menuflow.model.AdCampaign
import com.menuflow.model.AdCampaignStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Campanhas de anuncio do tenant (banco do TENANT). Sem filtro de escopo: db-per-tenant ja
 * isola por banco.
 *
 * [findByAdAccountIdAndIdempotencyKey] apoia a idempotencia (retry com a mesma chave devolve
 * a campanha existente em vez de recriar/gastar de novo). [existsByAdAccountIdAndStatus] e
 * [deleteByAdAccountId] apoiam o disconnect seguro da conta.
 */
@Repository
interface AdCampaignRepository : JpaRepository<AdCampaign, UUID> {

    fun findByAdAccountIdAndIdempotencyKey(adAccountId: UUID, idempotencyKey: String): AdCampaign?

    fun findAllByOrderByCreatedAtDesc(): List<AdCampaign>

    fun existsByAdAccountIdAndStatus(adAccountId: UUID, status: AdCampaignStatus): Boolean

    /**
     * Existe alguma campanha desta conta JA criada na Meta (external_campaign_id != null)?
     * Usado pelo disconnect seguro: nao confiamos no status LOCAL (pode estar defasado por
     * drift entre a Meta e o commit local); a presenca do id externo prova que o objeto existe
     * na conta da Meta e ainda pode gastar — nao soltamos o token enquanto existir.
     */
    fun existsByAdAccountIdAndExternalCampaignIdNotNull(adAccountId: UUID): Boolean

    fun deleteByAdAccountId(adAccountId: UUID)
}
