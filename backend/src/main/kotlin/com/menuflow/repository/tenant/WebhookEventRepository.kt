package com.menuflow.repository.tenant

import com.menuflow.model.WebhookEvent
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface WebhookEventRepository : JpaRepository<WebhookEvent, UUID> {

    /** Evento ja recebido? Base da deduplicacao de webhooks (idempotencia). */
    fun existsByEventId(eventId: String): Boolean
}
