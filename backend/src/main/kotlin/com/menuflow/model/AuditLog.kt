package com.menuflow.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * Trilha de auditoria (append-only) no banco do TENANT. Registra quem fez o quê,
 * em qual entidade, com snapshot antes/depois em JSONB. Sem @PreUpdate e sem
 * setters de negócio: uma linha de auditoria nunca é alterada após gravada.
 *
 * before_json/after_json são strings de JSON já serializado, mapeadas para colunas
 * JSONB via @JdbcTypeCode(SqlTypes.JSON) (Hibernate 6 grava/lê o documento cru).
 */
@Entity
@Table(name = "audit_log")
class AuditLog(
    @Column(name = "actor_user_id", nullable = false)
    val actorUserId: UUID,

    @Column(nullable = false, length = 64)
    val action: String,

    @Column(nullable = false, length = 64)
    val entity: String,

    @Column(name = "actor_role", length = 32)
    val actorRole: String? = null,

    @Column(name = "entity_id")
    val entityId: UUID? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_json", columnDefinition = "jsonb")
    val beforeJson: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_json", columnDefinition = "jsonb")
    val afterJson: String? = null,

    @Column(columnDefinition = "text")
    val reason: String? = null,

    @Column(length = 45)
    val ip: String? = null,

    @Column(name = "user_agent", columnDefinition = "text")
    val userAgent: String? = null,

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
