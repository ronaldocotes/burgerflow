package com.menuflow.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Criativo (texto + imagem) de uma [AdCampaign] (Fase 8.2). Vive no banco do TENANT,
 * espelha db/tenant/migration/V60__ad_creative (na V60). FK campaign_id ON DELETE CASCADE:
 * apagar a campanha local apaga o criativo junto.
 *
 * [imageHash] e o hash devolvido pelo upload em /act_{id}/adimages da Meta; nullable (o
 * criativo pode ser link-only se o produto nao tiver foto). [productId] aponta a foto do
 * catalogo, sem relacao JPA (modulo desacoplado).
 */
@Entity
@Table(name = "ad_creative")
class AdCreative(
    @Column(name = "campaign_id", nullable = false)
    var campaignId: UUID,

    @Column(name = "primary_text", columnDefinition = "text")
    var primaryText: String? = null,

    @Column(length = 200)
    var headline: String? = null,

    @Column(length = 40)
    var cta: String? = null,

    @Column(name = "product_id")
    var productId: UUID? = null,

    @Column(name = "image_hash", length = 120)
    var imageHash: String? = null,

    @Column(name = "approval_status", length = 40)
    var approvalStatus: String? = null,

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
