package com.menuflow.repository.tenant

import com.menuflow.model.AuditLog
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface AuditLogRepository : JpaRepository<AuditLog, UUID> {
    fun findAllByEntityAndEntityId(entity: String, entityId: UUID, pageable: Pageable): Page<AuditLog>
    fun findAllByEntity(entity: String, pageable: Pageable): Page<AuditLog>
}
