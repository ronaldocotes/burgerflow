package com.menuflow.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.menuflow.model.BotHandoff
import java.time.Instant
import java.util.UUID

/**
 * Corpo do webhook do WAHA (Fase 4.3). So mapeamos os campos que usamos; o resto e
 * ignorado (o WAHA envia muitos campos extras conforme o evento).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class WahaWebhookBody(
    val event: String? = null,
    val session: String? = null,
    val payload: WahaWebhookPayload? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WahaWebhookPayload(
    /** Id da mensagem (chave de idempotencia). */
    val id: String? = null,
    /** Remetente no formato "5511999999999@c.us". */
    val from: String? = null,
    /** Texto da mensagem. */
    val body: String? = null,
    /** true quando a mensagem foi enviada pelo proprio numero (eco) — ignoramos. */
    val fromMe: Boolean = false,
    val timestamp: Long? = null,
)

/** Handoff (transferencia para atendente humano) exposto na API admin. */
data class BotHandoffResponse(
    val id: UUID,
    val customerPhone: String,
    val lastBotMessage: String?,
    val resolved: Boolean,
    val createdAt: Instant,
    val resolvedAt: Instant?,
) {
    companion object {
        fun from(h: BotHandoff) = BotHandoffResponse(
            id = h.id!!,
            customerPhone = h.customerPhone,
            lastBotMessage = h.lastBotMessage,
            resolved = h.resolved,
            createdAt = h.createdAt,
            resolvedAt = h.resolvedAt,
        )
    }
}
