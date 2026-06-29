package com.menuflow.dto

import com.menuflow.model.TrackingLink
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/**
 * Criacao de link rastreavel (Fase 3.6). O slug e a destinationUrl sao gerados no
 * servico (slug aleatorio unico + UTM montado a partir de source/medium/campaign).
 */
data class TrackingLinkCreateRequest(
    @field:NotBlank @field:Size(max = 100) val name: String,
    @field:NotBlank @field:Size(max = 100) val source: String,
    @field:Size(max = 100) val medium: String? = null,
    @field:Size(max = 100) val campaign: String? = null,
)

/**
 * Edicao de link (PATCH): so name e active. source/medium/campaign/destinationUrl/slug
 * sao imutaveis apos criados (mudar a origem invalidaria a atribuicao historica).
 * Campos nulos sao ignorados (PATCH parcial) — null != "limpar".
 */
data class TrackingLinkUpdateRequest(
    @field:Size(max = 100) val name: String? = null,
    val active: Boolean? = null,
)

data class TrackingLinkResponse(
    val id: UUID,
    val slug: String,
    val name: String,
    val source: String,
    val medium: String?,
    val campaign: String?,
    val destinationUrl: String,
    val active: Boolean,
    val clickCount: Long,
    val createdAt: Instant,
    /** URL curta para compartilhar (https://.../r/{slug}); montada no servico. */
    val shareUrl: String,
) {
    companion object {
        fun from(t: TrackingLink, shareUrl: String) = TrackingLinkResponse(
            id = t.id!!,
            slug = t.slug,
            name = t.name,
            source = t.source,
            medium = t.medium,
            campaign = t.campaign,
            destinationUrl = t.destinationUrl,
            active = t.active,
            clickCount = t.clickCount,
            createdAt = t.createdAt,
            shareUrl = shareUrl,
        )
    }
}

/** Linha do painel ROAS: cliques x conversoes x receita por link. */
data class TrackingSummaryResponse(
    val trackingLinkId: UUID,
    val name: String,
    val source: String,
    val slug: String,
    val clicks: Long,
    val conversions: Long,
    val revenueCents: Long,
    /** conversions / clicks; 0.0 quando nao houve cliques (NaN-safe). */
    val conversionRate: Double,
)

/**
 * Resposta do clique publico (GET /public/{slug}/r/{trackingSlug}). O frontend
 * Next.js faz o redirect para destinationUrl; o backend NAO emite 302 (o front
 * gerencia o redirecionamento e a UX de carregamento).
 */
data class TrackingRedirectResponse(
    val destinationUrl: String,
    val slug: String,
    val source: String,
    val medium: String?,
    val campaign: String?,
)
