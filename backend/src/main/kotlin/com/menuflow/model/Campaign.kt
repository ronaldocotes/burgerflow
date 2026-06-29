package com.menuflow.model

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * Campanha de marketing por WhatsApp (Fase 3.4). Vive no banco do TENANT
 * (db-per-tenant), entao nao tem coluna de escopo. Os destinatarios sao
 * pre-calculados em [CampaignSend] no momento da criacao (status QUEUED) e
 * despachados em segundo plano pelo CampaignDispatcher com delay anti-ban.
 */
@Entity
@Table(name = "campaigns")
class Campaign(
    @Column(nullable = false, length = 200)
    var name: String,

    /** Texto base; suporta as variaveis {nome}, {pontos} e {dias}. */
    @Column(name = "message_template", nullable = false, columnDefinition = "text")
    var messageTemplate: String,

    @Column(nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    var segment: CampaignSegment,

    /**
     * Parametros do segmento (JSONB). Guardado para uso futuro de segmentacao
     * fina; nesta fase as bandas de RFV usam os limiares fixos do RfvService.
     * String de JSON ja serializado, mapeada para JSONB (mesmo padrao do AuditLog).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "segment_params", columnDefinition = "jsonb")
    var segmentParams: String? = null,

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    var status: CampaignStatus = CampaignStatus.DRAFT,

    @Column(name = "scheduled_at")
    var scheduledAt: Instant? = null,

    @Column(name = "started_at")
    var startedAt: Instant? = null,

    @Column(name = "completed_at")
    var completedAt: Instant? = null,

    @Column(name = "total_recipients", nullable = false)
    var totalRecipients: Int = 0,

    @Column(name = "sent_count", nullable = false)
    var sentCount: Int = 0,

    @Column(name = "failed_count", nullable = false)
    var failedCount: Int = 0,

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

/** Segmento-alvo da campanha. As bandas RFV_* derivam do RfvService. */
enum class CampaignSegment {
    RFV_INACTIVE,
    RFV_AT_RISK,
    RFV_LOYAL,
    ALL_OPT_IN,
    CUSTOM,
}

/** Ciclo de vida da campanha. */
enum class CampaignStatus {
    DRAFT,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
}
