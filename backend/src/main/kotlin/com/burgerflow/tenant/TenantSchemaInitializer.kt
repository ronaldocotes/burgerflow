package com.burgerflow.tenant

import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.datasource.init.ScriptUtils
import javax.sql.DataSource

/**
 * Materializes the business schema inside a freshly provisioned tenant database
 * by running the idempotent baseline DDL script. Predictable and Flyway-ready:
 * in production this becomes a Flyway "migrate per tenant" step + a version
 * ledger in the control DB (see conhecimento fronteira-2026 multi-tenant note).
 */
class TenantSchemaInitializer {

    private val log = LoggerFactory.getLogger(javaClass)

    fun initialize(dataSource: DataSource) {
        val script = ClassPathResource("db/tenant-baseline.sql")
        dataSource.connection.use { conn ->
            ScriptUtils.executeSqlScript(conn, script)
        }
        log.info("Tenant baseline schema ensured")
    }
}
