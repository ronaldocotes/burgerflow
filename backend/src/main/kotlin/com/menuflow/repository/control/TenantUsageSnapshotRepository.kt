package com.menuflow.repository.control

import com.menuflow.platform.TenantUsageSnapshot
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

/**
 * Acesso ao banco de CONTROLE para snapshots de uso. Padrão idêntico ao
 * TenantRepository: @Repository + JpaRepository sem qualifiers adicionais
 * (o EMF de controle é o único EMF configurado para este pacote via package scan).
 *
 * Uso típico:
 *  - findFirstByTenantIdOrderBySnapshotDateDesc → último snapshot de um tenant
 *  - findByTenantIdAndSnapshotDate              → idempotência/guard do job noturno
 */
@Repository
interface TenantUsageSnapshotRepository : JpaRepository<TenantUsageSnapshot, UUID> {

    /** Último snapshot gravado para o tenant; null se ainda não há dados. */
    fun findFirstByTenantIdOrderBySnapshotDateDesc(tenantId: UUID): TenantUsageSnapshot?

    /** Snapshot de uma data específica (guard para UPSERT idempotente do job). */
    fun findByTenantIdAndSnapshotDate(tenantId: UUID, date: LocalDate): TenantUsageSnapshot?
}
