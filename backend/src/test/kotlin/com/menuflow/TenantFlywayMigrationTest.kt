package com.menuflow

import com.menuflow.tenant.DynamicTenantRoutingDataSource
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import javax.sql.DataSource

/**
 * Verifies the Flyway-per-tenant pipeline:
 *  1. provisioning a NEW tenant runs V1 -> the tenant's `schema_version` history
 *     table has exactly one applied row;
 *  2. provisioning the SAME tenant again is a no-op (Flyway detects HEAD) -> the
 *     history table still has exactly one row;
 *  3. every successful migrate appends a row to the control ledger
 *     (`tenant_migration_log`).
 *
 * We trigger provisioning by binding a tenant and opening a routed connection —
 * the first access lazily builds the pool and calls TenantFlywayMigrator.
 */
class TenantFlywayMigrationTest @Autowired constructor(
    @Qualifier("tenantRoutingDataSource") private val routing: DynamicTenantRoutingDataSource,
    @Qualifier("controlDataSource") private val controlDs: DataSource,
) : IntegrationTestBase() {

    @AfterEach
    fun clear() = TenantContext.clear()

    /** Forces pool creation + Flyway by opening (and closing) one routed connection. */
    private fun provision(slug: String) {
        TenantContext.set(slug)
        routing.connection.use { /* first access materializes the schema */ }
        TenantContext.clear()
    }

    private fun countSchemaVersionRows(slug: String): Int {
        TenantContext.set(slug)
        return routing.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM schema_version WHERE success = true").use { rs ->
                    rs.next(); rs.getInt(1)
                }
            }
        }.also { TenantContext.clear() }
    }

    private fun ledgerCount(slug: String): Int =
        controlDs.connection.use { conn ->
            conn.prepareStatement(
                "SELECT count(*) FROM tenant_migration_log WHERE tenant_slug = ? AND success = true",
            ).use { ps ->
                ps.setString(1, slug)
                ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
        }

    @Test
    fun `provisioning a new tenant applies V1 and records the ledger`() {
        val slug = "flywaytenant1"

        provision(slug)

        // V1..V13 applied exactly once each in the tenant's history table.
        assertEquals(13, countSchemaVersionRows(slug), "tenant schema_version must have exactly thirteen applied migrations (V1..V13)")

        // Ledger got at least one success row for this tenant.
        assertTrue(ledgerCount(slug) >= 1, "control ledger must record the successful migration")
    }

    @Test
    fun `re-provisioning the same tenant is a no-op for the history table`() {
        val slug = "flywaytenant2"

        provision(slug)
        val firstCount = countSchemaVersionRows(slug)
        val firstLedger = ledgerCount(slug)
        assertEquals(13, firstCount)

        // Re-provision: the pool already exists, so we drop it to force a fresh
        // build that re-invokes the migrator against the SAME (already-migrated)
        // database — Flyway must detect HEAD and apply nothing new.
        routing.evictPool(slug)
        provision(slug)

        assertEquals(13, countSchemaVersionRows(slug), "re-running migrate must NOT add duplicate rows")
        // The migrator still logs each invocation; a fresh successful no-op run
        // appends one more ledger row (audit of the attempt), so it grows by one.
        assertEquals(firstLedger + 1, ledgerCount(slug), "each migrate invocation appends one ledger row")
    }
}
