package com.burgerflow.tenant

import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.jdbc.datasource.init.ScriptUtils
import javax.sql.DataSource

/**
 * Materializes the business schema inside a freshly provisioned tenant database
 * by running idempotent DDL scripts. Predictable and Flyway-ready: in production
 * this becomes a Flyway "migrate per tenant" step + a version ledger in the
 * control DB (see conhecimento fronteira-2026 multi-tenant note).
 *
 * Order of application (all scripts are idempotent — IF NOT EXISTS):
 *   1. db/tenant-baseline.sql           (Sprint 1 baseline — Curador/Flyway owns it)
 *   2. db/pending sql files, sorted     (additive deltas not yet folded in)
 *
 * The db/pending step is a temporary bridge while the Curador wires up Flyway:
 * Sprint 2 tables (payments, delivery_drivers, refresh_tokens, order delivery
 * columns) live there so they exist at runtime without editing the migration
 * files. Once folded into Flyway, db/pending/ can be removed.
 */
class TenantSchemaInitializer {

    private val log = LoggerFactory.getLogger(javaClass)
    private val resolver = PathMatchingResourcePatternResolver()

    fun initialize(dataSource: DataSource) {
        dataSource.connection.use { conn ->
            ScriptUtils.executeSqlScript(conn, ClassPathResource("db/tenant-baseline.sql"))
            pendingScripts().forEach { script ->
                log.info("Applying pending tenant DDL: {}", script.filename)
                ScriptUtils.executeSqlScript(conn, script)
            }
        }
        log.info("Tenant baseline + pending schema ensured")
    }

    /** Pending delta scripts, sorted by filename for deterministic application. */
    private fun pendingScripts(): List<Resource> =
        runCatching {
            resolver.getResources("classpath:db/pending/*.sql")
                .filter { it.isReadable }
                .sortedBy { it.filename ?: "" }
        }.getOrElse {
            log.warn("Could not resolve db/pending scripts: {}", it.message)
            emptyList()
        }
}
