package com.menuflow.tenant

import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import javax.sql.DataSource

/**
 * Read-model for the migration drift-check endpoint.
 *
 * "Drift" = a tenant whose last successfully-applied tenant-schema version is
 * BEHIND the latest version bundled in classpath:db/tenant/migration. After a
 * new V<n> ships, tenants that have not been touched (their pool/first-access
 * has not re-run Flyway) will lag until they are migrated. This surfaces them.
 *
 * Source of truth:
 *  - latest available version  = highest V<n> in the tenant migration classpath
 *    (read once via a Flyway in `info`-only mode against the control DS — Flyway
 *    only scans the classpath here, it does not touch the control schema);
 *  - per-tenant applied version = the most recent SUCCESS row in
 *    control.tenant_migration_log for that slug.
 */
@Service
class TenantMigrationStatusService(
    @Qualifier("controlDataSource")
    private val controlDataSource: DataSource,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    data class TenantMigrationStatus(
        val tenantSlug: String,
        val appliedVersion: String?,
        val latestVersion: String,
        val drift: Boolean,
        val lastAppliedAt: String?,
        val lastSuccess: Boolean,
    )

    /** Highest tenant migration version present on the classpath (e.g. "1"). */
    fun latestAvailableVersion(): String {
        val flyway = Flyway.configure()
            .dataSource(controlDataSource) // only used to satisfy the builder; we read info from classpath
            .locations("classpath:db/tenant/migration")
            .table("schema_version")
            .load()
        // info().all() lists every migration resolved from the classpath; take the max.
        val versions = flyway.info().all().mapNotNull { it.version?.version }
        return versions.maxWithOrNull(::compareVersionStr) ?: "0"
    }

    /**
     * One status row per tenant that has at least one ledger entry, joined to its
     * latest SUCCESS version. Tenants never accessed yet won't appear (they have
     * no DB and no ledger row) — that is correct: there is nothing to drift.
     */
    fun statusForAllTenants(): List<TenantMigrationStatus> {
        val latest = latestAvailableVersion()
        val rows = mutableListOf<TenantMigrationStatus>()

        // Latest applied SUCCESS per slug + the most recent attempt's timestamp/outcome.
        val sql = """
            SELECT t.slug AS tenant_slug,
                   ls.version_applied AS applied_version,
                   la.applied_at      AS last_applied_at,
                   la.success         AS last_success
            FROM tenants t
            LEFT JOIN LATERAL (
                SELECT version_applied
                FROM tenant_migration_log
                WHERE tenant_slug = t.slug AND success = true
                ORDER BY applied_at DESC
                LIMIT 1
            ) ls ON true
            LEFT JOIN LATERAL (
                SELECT applied_at, success
                FROM tenant_migration_log
                WHERE tenant_slug = t.slug
                ORDER BY applied_at DESC
                LIMIT 1
            ) la ON true
            ORDER BY t.slug
        """.trimIndent()

        controlDataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val applied = rs.getString("applied_version")
                        val lastSuccess = rs.getObject("last_success") as? Boolean ?: false
                        rows += TenantMigrationStatus(
                            tenantSlug = rs.getString("tenant_slug"),
                            appliedVersion = applied,
                            latestVersion = latest,
                            drift = isBehind(applied, latest),
                            lastAppliedAt = rs.getTimestamp("last_applied_at")?.toInstant()?.toString(),
                            lastSuccess = lastSuccess,
                        )
                    }
                }
            }
        }
        return rows
    }

    /** true when [applied] is null or strictly lower than [latest]. */
    private fun isBehind(applied: String?, latest: String): Boolean {
        if (applied == null) return true
        return compareVersionStr(applied, latest) < 0
    }

    /** Compare dotted numeric versions ("1", "1.1", "2") leftmost-first. */
    private fun compareVersionStr(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val c = (pa.getOrNull(i) ?: 0).compareTo(pb.getOrNull(i) ?: 0)
            if (c != 0) return c
        }
        return 0
    }
}
