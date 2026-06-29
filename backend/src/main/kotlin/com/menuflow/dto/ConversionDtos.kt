package com.menuflow.dto

import com.menuflow.model.ConversionDispatch
import com.menuflow.model.ConversionPlatform
import com.menuflow.model.ConversionStatus
import java.time.Instant
import java.util.UUID

/**
 * Estado de um despacho de conversao para o painel admin. NAO expoe payload em claro
 * nem o token da plataforma — apenas metadados de auditoria (codigo de resposta,
 * tentativas, datas). responseBody fica fora do contrato de listagem de proposito.
 */
data class ConversionDispatchResponse(
    val id: UUID,
    val orderId: UUID,
    val platform: ConversionPlatform,
    val status: ConversionStatus,
    val eventId: String?,
    val responseCode: Int?,
    val attempts: Int,
    val lastAttemptAt: Instant?,
    val sentAt: Instant?,
    val createdAt: Instant,
) {
    companion object {
        fun from(d: ConversionDispatch) =
            ConversionDispatchResponse(
                id = d.id!!,
                orderId = d.orderId,
                platform = d.platform,
                status = d.status,
                eventId = d.eventId,
                responseCode = d.responseCode,
                attempts = d.attempts,
                lastAttemptAt = d.lastAttemptAt,
                sentAt = d.sentAt,
                createdAt = d.createdAt,
            )
    }
}
