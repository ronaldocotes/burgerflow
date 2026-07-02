package com.menuflow.platform

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Snapshot diário de métricas de uso por tenant, gravado no banco de CONTROLE
 * por um job noturno. Evita consulta N-bancos síncrona na listagem do painel.
 *
 * Espelha db/control/migration/V13__tenant_usage_snapshot.sql (ddl-auto=validate).
 *
 * Append-only na prática: o job faz INSERT … ON CONFLICT (tenant_id, snapshot_date)
 * DO UPDATE — portanto não há @PreUpdate aqui; a entidade é tratada como imutável
 * pela aplicação após a primeira persistência do dia.
 *
 * tenantId é FK para tenants(id) mas mapeado como UUID simples (sem @ManyToOne)
 * para evitar carregamento desnecessário do agregado Tenant em consultas de painel.
 */
@Entity
@Table(name = "tenant_usage_snapshot")
class TenantUsageSnapshot(

    @Column(name = "tenant_id", nullable = false)
    val tenantId: UUID,

    @Column(name = "snapshot_date", nullable = false)
    val snapshotDate: LocalDate,

    @Column(name = "orders_month", nullable = false)
    val ordersMonth: Long = 0,

    @Column(name = "db_size_mb", nullable = false)
    val dbSizeMb: Long = 0,

    @Column(name = "last_login_at")
    val lastLoginAt: Instant? = null,

    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
