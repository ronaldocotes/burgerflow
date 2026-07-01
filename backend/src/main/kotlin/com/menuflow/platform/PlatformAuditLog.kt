package com.menuflow.platform

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
 * Trilha de auditoria APPEND-ONLY do painel super-admin, no banco de CONTROLE.
 * Espelha db/control/migration/V12__platform_audit_log.sql (validate no boot).
 *
 * Sem @PreUpdate e sem setters de negócio: uma linha de auditoria jamais é alterada
 * depois de gravada. before_json/after_json NUNCA contêm segredos/PII em claro —
 * quem chama mascara antes (padrão: só ids, slugs, flags e papéis).
 *
 * id é BIGSERIAL no banco => IDENTITY + Long (diferente das demais entidades, que
 * usam UUID). É o único campo gerado pelo banco aqui.
 */
@Entity
@Table(name = "platform_audit_log")
class PlatformAuditLog(
    @Column(name = "actor_user_id", nullable = false)
    val actorUserId: UUID,

    @Column(name = "actor_email", nullable = false, length = 255)
    val actorEmail: String,

    @Column(nullable = false, length = 60)
    val action: String,

    @Column(name = "target_tenant_id")
    val targetTenantId: UUID? = null,

    @Column(name = "target_entity", length = 60)
    val targetEntity: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_json", columnDefinition = "jsonb")
    val beforeJson: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_json", columnDefinition = "jsonb")
    val afterJson: String? = null,

    @Column(name = "source_ip", length = 45)
    val sourceIp: String? = null,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
