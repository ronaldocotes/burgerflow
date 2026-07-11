package com.menuflow.ads

import com.menuflow.model.AdCampaign
import com.menuflow.model.AdCampaignStatus
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/**
 * Corpo do POST /ads/campaigns (criar campanha). A verba vem em CENTAVOS (piso/teto sao
 * validados no service, NAO por bean-validation: o teto depende do entitlement do tenant).
 * O Idempotency-Key vai no HEADER, nao aqui.
 *
 * Bean-validation aqui e so o guard-rail estrutural (sinal/limites geograficos); a regra
 * monetaria de verdade (piso R$10, teto do tenant, fail-closed) mora no AdCampaignService.
 */
data class CreateAdCampaignRequest(
    @field:NotNull
    val accountId: UUID?,

    @field:NotBlank
    @field:Size(max = 200)
    val name: String,

    /** Verba diaria em centavos na moeda da conta. Piso/teto no service. */
    @field:NotNull
    @field:Positive
    val dailyBudgetCents: Long?,

    // Nota: @DecimalMin/@DecimalMax NAO suportam Double (UnexpectedTypeException no bootstrap
    // do validator — licao CONFIG-A). O range de lat/lng e validado no AdCampaignService.
    @field:NotNull
    val geoLat: Double?,

    @field:NotNull
    val geoLng: Double?,

    /** Raio em km. Limite da Meta para custom_locations: 1..80. */
    @field:NotNull
    @field:Min(1) @field:Max(80)
    val radiusKm: Int?,

    /** URL de destino do anuncio (para onde o clique leva: cardapio/WhatsApp/etc.). */
    @field:NotBlank
    @field:Size(max = 500)
    val destinationUrl: String,

    /** Texto principal do anuncio (message/copy). */
    @field:NotBlank
    @field:Size(max = 2000)
    val primaryText: String,

    @field:Size(max = 200)
    val headline: String? = null,

    @field:Size(max = 40)
    val cta: String? = null,

    /** Produto do catalogo cuja foto vira a imagem do anuncio (opcional). */
    val productId: UUID? = null,

    /** Link de rastreio opcional (apenas registrado; sem efeito de spend nesta fase). */
    val trackingLinkId: UUID? = null,
)

/**
 * Campanha devolvida ao cliente. NAO carrega token nem detalhes internos de outra conta.
 * [dailyBudgetCents] em centavos (o frontend formata pela moeda da conta). [effectiveStatus]
 * e o espelho da Meta (pode ser null ate a primeira leitura/ativacao).
 */
data class AdCampaignResponse(
    val id: UUID,
    val adAccountId: UUID,
    val name: String,
    val objective: String,
    val status: AdCampaignStatus,
    val effectiveStatus: String?,
    val dailyBudgetCents: Long,
    val geoLat: Double?,
    val geoLng: Double?,
    val radiusKm: Int?,
    val externalCampaignId: String?,
    val createdAt: Instant,
) {
    companion object {
        fun from(c: AdCampaign) = AdCampaignResponse(
            id = c.id!!,
            adAccountId = c.adAccountId,
            name = c.name,
            objective = c.objective,
            status = c.status,
            effectiveStatus = c.effectiveStatus,
            dailyBudgetCents = c.dailyBudgetCents,
            geoLat = c.geoLat,
            geoLng = c.geoLng,
            radiusKm = c.radiusKm,
            externalCampaignId = c.externalCampaignId,
            createdAt = c.createdAt,
        )
    }
}

/** Corpo do PUT /ads/accounts/{id}/page — grava a Pagina do Facebook escolhida na conta. */
data class SetAdPageRequest(
    @field:NotBlank
    @field:Size(max = 50)
    val pageId: String,

    @field:Size(max = 200)
    val pageName: String? = null,
)

/** Uma Pagina do Facebook disponivel (GET /ads/accounts/{id}/pages). */
data class AdPageResponse(
    val id: String,
    val name: String?,
)
