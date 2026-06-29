package com.menuflow.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Envio individual de uma campanha a um cliente (Fase 3.4). Pre-calculado em massa
 * na criacao da campanha (status QUEUED, com a mensagem ja interpolada) e atualizado
 * pelo CampaignDispatcher conforme cada disparo. UNIQUE(campaign_id, customer_id)
 * garante 1 envio por cliente por campanha (idempotencia de destinatario).
 */
@Entity
@Table(name = "campaign_sends")
class CampaignSend(
    @Column(name = "campaign_id", nullable = false)
    val campaignId: UUID,

    @Column(name = "customer_id", nullable = false)
    val customerId: UUID,

    @Column(nullable = false, length = 20)
    val phone: String,

    /** Mensagem final, apos substituicao das variaveis e do prefixo de variacao. */
    @Column(nullable = false, columnDefinition = "text")
    val message: String,

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    var status: SendStatus = SendStatus.QUEUED,

    @Column(name = "sent_at")
    var sentAt: Instant? = null,

    @Column(name = "error_message", length = 500)
    var errorMessage: String? = null,

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)

/** Estado de um envio individual. OPT_OUT = cliente revogou antes do disparo. */
enum class SendStatus {
    QUEUED,
    SENT,
    FAILED,
    OPT_OUT,
}
