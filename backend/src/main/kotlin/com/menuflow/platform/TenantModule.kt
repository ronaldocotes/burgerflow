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
 * Override de entitlement de módulo por tenant, no banco de CONTROLE. Espelha
 * db/control/migration/V10__tenant_module.sql. O controle roda hbm2ddl=validate,
 * então os @Column abaixo precisam bater EXATAMENTE com a migração.
 *
 * Ausência de linha (tenant_id, module_key) => vale o default do plano (ver
 * ModuleKey.defaultEnabledFor). A presença desta linha força o valor de enabled.
 *
 * tenantId é UUID SEM relação JPA mapeada (a FK existe no banco, ON DELETE CASCADE)
 * — o módulo platform não navega o grafo, apenas consulta escopado por tenant.
 */
@Entity
@Table(name = "tenant_module")
class TenantModule(
    @Column(name = "tenant_id", nullable = false)
    var tenantId: UUID,

    @Column(name = "module_key", nullable = false, length = 40)
    var moduleKey: String,

    @Column(nullable = false)
    var enabled: Boolean,

    @Column(name = "updated_by_user_id", nullable = false)
    var updatedByUserId: UUID,

    // Limites específicos do módulo (ex.: {"ai_monthly_token_cap": 2000000}).
    // JSON cru mapeado para a coluna JSONB via @JdbcTypeCode (mesmo padrão do
    // audit_log por-tenant). Nullable: a maioria dos toggles não precisa de limite.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "limits_json", columnDefinition = "jsonb")
    var limitsJson: String? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,
)
