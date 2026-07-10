package com.menuflow.repository.tenant

import com.menuflow.model.AdMetricsSnapshot
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

/**
 * Snapshots diarios de metricas de anuncio (banco do TENANT). Sem filtro de escopo:
 * db-per-tenant ja isola por banco.
 *
 * [findByAdAccountIdAndSnapshotDate] apoia o upsert idempotente do job (acha a linha do
 * dia para atualizar em vez de duplicar). A leitura do grafico usa
 * [findByAdAccountIdAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc].
 */
@Repository
interface AdMetricsSnapshotRepository : JpaRepository<AdMetricsSnapshot, UUID> {

    fun findByAdAccountIdAndSnapshotDate(adAccountId: UUID, snapshotDate: LocalDate): AdMetricsSnapshot?

    fun findByAdAccountIdAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(
        adAccountId: UUID,
        snapshotDate: LocalDate,
    ): List<AdMetricsSnapshot>
}
