package com.menuflow.model

import jakarta.persistence.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Snapshot diario (nivel CONTA) de metricas de anuncio da Meta (Fase 8.1). Vive no banco
 * do TENANT (db-per-tenant), sem coluna de escopo. Espelha
 * db/tenant/migration/V59__ad_metrics_snapshot.sql.
 *
 * Uma linha por (conta, dia). O job horario faz upsert idempotente por
 * (adAccountId, snapshotDate) — a UNIQUE da V59 garante que re-rodar o dia atualize em
 * vez de duplicar. O dia corrente vem com [isPartial] = true (ainda consolidando na Meta).
 *
 * Dinheiro SEMPRE em centavos (Long) na moeda da conta; nunca float. [ctrMilli] = CTR%
 * * 1000 (ex.: 1.5% -> 1500).
 *
 * [adAccountId] e um UUID puro (sem relacao JPA @ManyToOne) de proposito: o service so
 * precisa do id para o upsert/consulta, e evitar o relacionamento mantem o mapeamento
 * simples e as queries previsiveis.
 */
@Entity
@Table(name = "ad_metrics_snapshot")
class AdMetricsSnapshot(
    @Column(name = "ad_account_id", nullable = false)
    var adAccountId: UUID,

    @Column(name = "snapshot_date", nullable = false)
    var snapshotDate: LocalDate,

    @Column(name = "spend_cents", nullable = false)
    var spendCents: Long = 0,

    @Column(nullable = false)
    var impressions: Long = 0,

    @Column(nullable = false)
    var reach: Long = 0,

    @Column(nullable = false)
    var clicks: Long = 0,

    @Column(name = "ctr_milli", nullable = false)
    var ctrMilli: Int = 0,

    @Column(name = "cpc_cents", nullable = false)
    var cpcCents: Long = 0,

    @Column(name = "is_partial", nullable = false)
    var isPartial: Boolean = false,

    @Column(name = "fetched_at", nullable = false)
    var fetchedAt: Instant = Instant.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,
)
