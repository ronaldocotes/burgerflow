package com.menuflow.dto

import com.menuflow.model.AuditLog
import java.time.Instant
import java.util.UUID

/**
 * Resposta da trilha de auditoria. beforeJson/afterJson são devolvidos como string
 * de JSON cru (o cliente pode parsear); os demais campos são escalares.
 */
data class AuditLogResponse(
    val id: UUID,
    val actorUserId: UUID,
    val actorRole: String?,
    val action: String,
    val entity: String,
    val entityId: UUID?,
    val beforeJson: String?,
    val afterJson: String?,
    val reason: String?,
    val createdAt: Instant,
) {
    companion object {
        fun from(a: AuditLog) = AuditLogResponse(
            id = a.id!!,
            actorUserId = a.actorUserId,
            actorRole = a.actorRole,
            action = a.action,
            entity = a.entity,
            entityId = a.entityId,
            beforeJson = a.beforeJson,
            afterJson = a.afterJson,
            reason = a.reason,
            createdAt = a.createdAt,
        )
    }
}
