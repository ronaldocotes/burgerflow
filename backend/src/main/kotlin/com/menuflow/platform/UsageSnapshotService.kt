package com.menuflow.platform

import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.model.control.Tenant
import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.control.TenantUsageSnapshotRepository
import com.menuflow.repository.control.UserRepository
import com.menuflow.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * Coleta e persiste métricas de uso por tenant (snapshot diário).
 *
 * Arquitetura de acesso a dados:
 *  - Pedidos do mês: JDBC direto na [tenantDataSource] (routing datasource) com
 *    TenantContext setado manualmente; não usa JPA para evitar conflito de tx.
 *  - Tamanho do banco: pg_database_size() via [controlDataSource] (qualquer
 *    conexão Postgres tem acesso à função de catálogo).
 *  - Último login: [UserRepository] no banco de controle (JPQL @Query).
 *  - Persistência: TransactionTemplate no controlTransactionManager garante
 *    atomicidade do delete+save (UPSERT sem native query, pois o id é UUID
 *    gerado a cada nova instância — impossível usar merge diretamente).
 *
 * Fail-open por tenant: falha em um não interrompe os demais (catch em snapshotAll).
 */
@Service
class UsageSnapshotService(
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val snapshotRepository: TenantUsageSnapshotRepository,
    @Qualifier("tenantRoutingDataSource") private val tenantDataSource: DataSource,
    @Qualifier("controlDataSource") private val controlDataSource: DataSource,
    @Qualifier("controlTransactionManager") txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val txTemplate = TransactionTemplate(txManager)

    // ── Ponto de entrada do scheduler ────────────────────────────────────────

    /** Percorre todos os tenants ativos e gera (ou atualiza) o snapshot do dia. */
    fun snapshotAll() {
        val tenants = tenantRepository.findAll().filter { it.isActive }
        log.info("[snapshot] iniciando snapshot de {} tenant(s)", tenants.size)
        for (tenant in tenants) {
            runCatching { snapshotOne(tenant) }
                .onFailure { log.error("[snapshot] falha tenant '{}': {}", tenant.slug, it.message) }
        }
        log.info("[snapshot] concluído")
    }

    // ── Leitura de uso (exposta ao controller para GET /usage) ───────────────

    /**
     * Retorna o snapshot mais recente do tenant. Se não houver nenhum (ex.: tenant
     * recém-provisionado antes do primeiro job noturno), executa um snapshot síncrono
     * e tenta novamente. Em caso de falha retorna zeros com snapshotDate=null.
     */
    fun getUsage(slug: String): TenantUsageResponse {
        val tenant = tenantRepository.findBySlug(slug)
            ?: throw ResourceNotFoundException("Tenant não encontrado: $slug")
        val tenantId = tenant.id!!

        val snapshot = snapshotRepository.findFirstByTenantIdOrderBySnapshotDateDesc(tenantId)
        if (snapshot != null) {
            return snapshot.toResponse()
        }
        // Sem snapshot: gerar sincronamente (tolerante a falha).
        log.info("[snapshot] nenhum snapshot para '{}', gerando síncronamente", slug)
        return try {
            snapshotOne(tenant)
            snapshotRepository.findFirstByTenantIdOrderBySnapshotDateDesc(tenantId)
                ?.toResponse()
                ?: TenantUsageResponse(0L, 0L, null, null)
        } catch (e: Exception) {
            log.warn("[snapshot] snapshot síncrono falhou para '{}': {}", slug, e.message)
            TenantUsageResponse(0L, 0L, null, null)
        }
    }

    // ── Snapshot individual ──────────────────────────────────────────────────

    /** Coleta métricas do tenant e persiste (UPSERT) no banco de controle. */
    fun snapshotOne(tenant: Tenant) {
        val tenantId = tenant.id ?: return
        val today = LocalDate.now()

        val ordersMonth = queryOrdersThisMonth(tenant.slug)
        val dbSizeMb = queryDbSizeMb(tenant.slug)
        val lastLoginAt = userRepository.findMaxLastLoginAtByTenantId(tenantId)

        // UPSERT atômico: delete+save dentro da mesma transação de controle.
        txTemplate.executeWithoutResult {
            snapshotRepository.findByTenantIdAndSnapshotDate(tenantId, today)
                ?.also { snapshotRepository.delete(it) }
            snapshotRepository.save(
                TenantUsageSnapshot(
                    tenantId = tenantId,
                    snapshotDate = today,
                    ordersMonth = ordersMonth,
                    dbSizeMb = dbSizeMb,
                    lastLoginAt = lastLoginAt,
                ),
            )
        }
        log.debug(
            "[snapshot] tenant='{}' orders={} dbMb={} lastLogin={}",
            tenant.slug, ordersMonth, dbSizeMb, lastLoginAt,
        )
    }

    // ── Queries de métricas (JDBC direto) ───────────────────────────────────

    /**
     * Conta pedidos criados desde o início do mês corrente (UTC).
     * Troca TenantContext para rotear a conexão ao banco do tenant.
     */
    private fun queryOrdersThisMonth(slug: String): Long {
        val startOfMonth = LocalDate.now()
            .withDayOfMonth(1)
            .atTime(LocalTime.MIDNIGHT)
            .toInstant(ZoneOffset.UTC)
        val previous = TenantContext.get()
        TenantContext.set(slug)
        return try {
            tenantDataSource.connection.use { conn ->
                conn.prepareStatement(
                    "SELECT COUNT(*) FROM orders WHERE created_at >= ?",
                ).use { ps ->
                    ps.setTimestamp(1, Timestamp.from(startOfMonth))
                    ps.executeQuery().use { rs ->
                        if (rs.next()) rs.getLong(1) else 0L
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("[snapshot] erro ao contar pedidos do tenant '{}': {}", slug, e.message)
            0L
        } finally {
            if (previous != null) TenantContext.set(previous) else TenantContext.clear()
        }
    }

    /**
     * Tamanho do banco tenant_{slug} em MiB via pg_database_size() no banco de
     * controle (qualquer conexão Postgres tem acesso à função de catálogo).
     */
    private fun queryDbSizeMb(slug: String): Long {
        return try {
            controlDataSource.connection.use { conn ->
                conn.prepareStatement(
                    "SELECT pg_database_size(CONCAT('tenant_', ?)) / 1048576",
                ).use { ps ->
                    ps.setString(1, slug)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) rs.getLong(1) else 0L
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("[snapshot] erro ao medir banco do tenant '{}': {}", slug, e.message)
            0L
        }
    }

    // ── Mapeamento ───────────────────────────────────────────────────────────

    private fun TenantUsageSnapshot.toResponse() = TenantUsageResponse(
        ordersThisMonth = ordersMonth,
        dbSizeMb = dbSizeMb,
        lastLoginAt = lastLoginAt,
        snapshotDate = snapshotDate,
    )
}
