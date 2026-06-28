package com.menuflow.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Registro append-only de eventos de webhook ja processados (deduplicacao). Vive no
 * banco do TENANT. O event_id e o id do evento no Asaas; reentrega do MESMO evento
 * cai na constraint UNIQUE e e ignorada (idempotencia do webhook).
 */
@Entity
@Table(name = "webhook_events")
data class WebhookEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "event_id", unique = true, nullable = false)
    val eventId: String,

    @Column(name = "received_at", nullable = false, updatable = false)
    val receivedAt: Instant = Instant.now(),
)
