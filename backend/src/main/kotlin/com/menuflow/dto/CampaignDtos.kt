package com.menuflow.dto

import com.menuflow.model.Campaign
import com.menuflow.model.CampaignSegment
import com.menuflow.model.CampaignSend
import com.menuflow.model.CampaignStatus
import com.menuflow.model.RfvScore
import com.menuflow.model.RfvSegment
import com.menuflow.model.SendStatus
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/**
 * Criacao de campanha (Fase 3.4). O segmento define os destinatarios; o template
 * suporta {nome}, {pontos} e {dias}. A lista de destinatarios e calculada no servidor
 * (nunca enviada pelo cliente) e limitada pelo campaign_daily_limit do tenant.
 */
data class CampaignCreateRequest(
    @field:NotBlank @field:Size(max = 200) val name: String,
    @field:NotBlank @field:Size(max = 4000) val messageTemplate: String,
    val segment: CampaignSegment,
    /** Parametros do segmento (uso futuro de segmentacao fina); opcional. */
    val segmentParams: Map<String, Any?>? = null,
    val scheduledAt: Instant? = null,
)

data class CampaignResponse(
    val id: UUID,
    val name: String,
    val messageTemplate: String,
    val segment: CampaignSegment,
    val segmentParams: String?,
    val status: CampaignStatus,
    val scheduledAt: Instant?,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val totalRecipients: Int,
    val sentCount: Int,
    val failedCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(c: Campaign) = CampaignResponse(
            id = c.id!!,
            name = c.name,
            messageTemplate = c.messageTemplate,
            segment = c.segment,
            segmentParams = c.segmentParams,
            status = c.status,
            scheduledAt = c.scheduledAt,
            startedAt = c.startedAt,
            completedAt = c.completedAt,
            totalRecipients = c.totalRecipients,
            sentCount = c.sentCount,
            failedCount = c.failedCount,
            createdAt = c.createdAt,
            updatedAt = c.updatedAt,
        )
    }
}

data class CampaignSendResponse(
    val id: UUID,
    val customerId: UUID,
    val phone: String,
    val status: SendStatus,
    val sentAt: Instant?,
    val errorMessage: String?,
    val createdAt: Instant,
) {
    companion object {
        fun from(s: CampaignSend) = CampaignSendResponse(
            id = s.id!!,
            customerId = s.customerId,
            phone = s.phone,
            status = s.status,
            sentAt = s.sentAt,
            errorMessage = s.errorMessage,
            createdAt = s.createdAt,
        )
    }
}

/** Score RFV de um cliente para o painel (GET /rfv). Dinheiro em centavos. */
data class RfvScoreResponse(
    val customerId: UUID,
    val customerName: String?,
    val recencyDays: Int,
    val frequency: Int,
    val monetaryValue: Long,
    val segment: RfvSegment,
) {
    companion object {
        fun from(s: RfvScore) = RfvScoreResponse(
            customerId = s.customerId,
            customerName = s.customerName,
            recencyDays = s.recencyDays,
            frequency = s.frequency,
            monetaryValue = s.monetaryValue,
            segment = s.segment,
        )
    }
}
