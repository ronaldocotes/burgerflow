package com.menuflow.tenant

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationInfo
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

/**
 * Runs Flyway against a freshly provisioned tenant database and records the
 * outcome in the control DB ledger (`tenant_migration_log`).
 *
 * Why Flyway instead of the old ScriptUtils.executeSqlScript:
 *  - migrations are VERSIONED (V1, V2, ...) and tracked by checksum, so a
 *    tenant DB that is already at the latest version is a no-op on next access;
 *  - the history is auditable per tenant (the `schema_version` table);
 *  - schema evolution rolls FORWARD (new V<n>), never editing an applied file.
 *
 * The Flyway history table is named `schema_version` (not the default
 * `flyway_schema_history`) so it never collides with the control DB's own
 * Flyway history when both run on the same Postgres server.
 *
 * The control DataSource is injected lazily via [ObjectProvider]: this bean is
 * wired into the routing datasource, which is built before the control EMF is
 * ready, so we must resolve the control pool only when we actually log.
 */
@Component
class TenantFlywayMigrator(
    @Qualifier("controlDataSource")
    private val controlDataSourceProvider: ObjectProvider<DataSource>,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Migrate [tenantDataSource] to the latest tenant schema version. Idempotent:
     * if the tenant DB is already at HEAD, Flyway applies nothing. On success the
     * applied version is appended to the control ledger; on failure the error is
     * recorded and the exception is RELAUNCHED (never silenced) so the caller
     * fails closed — a tenant must not serve traffic on a half-migrated schema.
     */
    fun migrate(tenantSlug: String, tenantDataSource: DataSource) {
        val flyway = Flyway.configure()
            .dataSource(tenantDataSource)
            .locations("classpath:db/tenant/migration")
            .table("schema_version")
            // The first tenant access may hit a DB pre-seeded by the legacy
            // ScriptUtils path; baselineOnMigrate lets Flyway adopt a non-empty
            // schema as the baseline instead of refusing to run.
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load()

        try {
            val result = flyway.migrate()
            val versionApplied = currentVersion(flyway)
            log.info(
                "Tenant '{}' migrated: {} migration(s) applied, schema at version {}",
                tenantSlug, result.migrationsExecuted, versionApplied,
            )
            recordLedger(tenantSlug, versionApplied, success = true, errorMsg = null)
        } catch (ex: Exception) {
            log.error("Tenant '{}' migration FAILED", tenantSlug, ex)
            // Best-effort ledger of the failure; never let logging mask the cause.
            runCatching {
                recordLedger(tenantSlug, currentVersionSafe(flyway), success = false, errorMsg = ex.message)
            }.onFailure { log.warn("Could not record migration failure for '{}'", tenantSlug, it) }
            throw ex
        }
    }

    /** Latest version present in this tenant's `schema_version` after migrate. */
    private fun currentVersion(flyway: Flyway): String =
        flyway.info().current()?.version?.version ?: "0"

    private fun currentVersionSafe(flyway: Flyway): String =
        runCatching { currentVersion(flyway) }.getOrDefault("unknown")

    private fun recordLedger(
        tenantSlug: String,
        versionApplied: String,
        success: Boolean,
        errorMsg: String?,
    ) {
        val controlDs = controlDataSourceProvider.getObject()
        val sql = """
            INSERT INTO tenant_migration_log
                (tenant_slug, version_applied, applied_at, success, error_msg)
            VALUES (?, ?, ?, ?, ?)
        """.trimIndent()
        controlDs.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, tenantSlug)
                ps.setString(2, versionApplied)
                ps.setTimestamp(3, Timestamp.from(Instant.now()))
                ps.setBoolean(4, success)
                ps.setString(5, errorMsg?.take(2000))
                ps.executeUpdate()
            }
        }
    }

    @Suppress("unused")
    private fun MigrationInfo.shortDesc(): String = "$version: $description"
}
