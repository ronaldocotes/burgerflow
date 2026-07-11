package com.menuflow.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Campanha de anuncio propria da Meta (Fase 8.2). Vive no banco do TENANT (db-per-tenant),
 * sem coluna de escopo. Espelha db/tenant/migration/V60__ad_campaign.sql.
 *
 * SEGURANCA MONETARIA: a campanha NASCE PAUSED. Ativar e um endpoint separado que revalida
 * o teto de verba. A verba diaria e SEMPRE centavos (Long) na moeda da conta; nunca float.
 *
 * IDEMPOTENCIA: [idempotencyKey] + a UNIQUE (ad_account_id, idempotency_key) da V60 impedem
 * que um retry/double-click do request de criacao crie duas campanhas (dois gastos).
 *
 * [externalCampaignId]/[externalAdsetId]/[externalAdId] sao os ids na Meta, NULL enquanto a
 * saga de criacao externa nao completou (status DRAFT reservado). [adAccountId] e UUID puro
 * (sem @ManyToOne) — mesmo padrao das demais entidades do modulo ads.
 */
@Entity
@Table(name = "ad_campaign")
class AdCampaign(
    @Column(name = "ad_account_id", nullable = false)
    var adAccountId: UUID,

    @Column(nullable = false, length = 200)
    var name: String,

    @Column(name = "daily_budget_cents", nullable = false)
    var dailyBudgetCents: Long,

    @Column(name = "idempotency_key", nullable = false, length = 120)
    var idempotencyKey: String,

    @Column(nullable = false, length = 40)
    var objective: String = "OUTCOME_TRAFFIC",

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    var status: AdCampaignStatus = AdCampaignStatus.DRAFT,

    @Column(name = "effective_status", length = 40)
    var effectiveStatus: String? = null,

    @Column(name = "external_campaign_id", length = 50)
    var externalCampaignId: String? = null,

    @Column(name = "external_adset_id", length = 50)
    var externalAdsetId: String? = null,

    @Column(name = "external_ad_id", length = 50)
    var externalAdId: String? = null,

    @Column(name = "geo_lat")
    var geoLat: Double? = null,

    @Column(name = "geo_lng")
    var geoLng: Double? = null,

    @Column(name = "radius_km")
    var radiusKm: Int? = null,

    @Column(name = "tracking_link_id")
    var trackingLinkId: UUID? = null,

    /** Usuario (banco de CONTROLE) que criou. UUID sem relacao JPA (cross-db). */
    @Column(name = "created_by_user_id")
    var createdByUserId: UUID? = null,

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun preUpdate() {
        updatedAt = Instant.now()
    }
}

/**
 * Status LOCAL da campanha (a intencao do usuario), distinto do effective_status da Meta.
 *  - DRAFT: linha reservada durante a saga de criacao externa (ainda sem ids da Meta);
 *  - PAUSED: campanha criada na Meta — NASCE assim (nunca gasta sem ativacao explicita);
 *  - ACTIVE: ativada pelo usuario (endpoint separado, revalida o teto de verba);
 *  - ARCHIVED: arquivada.
 * VARCHAR(20) sem CHECK na V60 => novos estados nao exigem migration.
 */
enum class AdCampaignStatus { DRAFT, PAUSED, ACTIVE, ARCHIVED }
