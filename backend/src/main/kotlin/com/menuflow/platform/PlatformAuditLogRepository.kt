package com.menuflow.platform

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Leitura/escrita da trilha de plataforma (banco de CONTROLE). Append-only: a
 * aplicação só faz INSERT (save) e SELECT; nunca UPDATE/DELETE de linha existente.
 */
@Repository
interface PlatformAuditLogRepository : JpaRepository<PlatformAuditLog, Long> {
    fun findByTargetTenantIdOrderByCreatedAtDesc(targetTenantId: UUID, pageable: Pageable): Page<PlatformAuditLog>
}
