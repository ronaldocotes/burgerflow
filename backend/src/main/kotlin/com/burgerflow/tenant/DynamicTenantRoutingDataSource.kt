package com.burgerflow.tenant

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/**
 * Routes every JPA connection to the physical database of the tenant currently
 * bound to [TenantContext]. One HikariCP pool is lazily created per tenant slug
 * and cached; the tenant database itself is created on first access when
 * [TenantDataSourceProperties.autoCreateDatabase] is true.
 *
 * This is the heart of database-per-tenant isolation: a request for tenant A and
 * a request for tenant B obtain connections to different physical databases, so
 * cross-tenant reads are impossible at the connection layer.
 */
class DynamicTenantRoutingDataSource(
    private val props: TenantDataSourceProperties,
    /**
     * Invoked once, right after a NEW tenant pool is created, to materialize the
     * business schema in that tenant database (Hibernate schema update). Null for
     * the control pool. Set by TenantDataSourceConfig after the EMF is ready.
     */
    @Volatile var schemaInitializer: ((DataSource) -> Unit)? = null,
) : AbstractRoutingDataSource() {

    private val log = LoggerFactory.getLogger(javaClass)
    private val pools = ConcurrentHashMap<String, HikariDataSource>()

    init {
        // AbstractRoutingDataSource requires a non-empty target map + default.
        // We manage real pools ourselves, so we hand it a placeholder map and
        // override determineTargetDataSource entirely.
        setTargetDataSources(HashMap<Any, Any>())
        setDefaultTargetDataSource(controlPool())
        afterPropertiesSet()
    }

    override fun determineCurrentLookupKey(): Any? = TenantContext.get()

    override fun determineTargetDataSource(): DataSource {
        val slug = TenantContext.get()
            ?: throw IllegalStateException("No tenant bound to the current request; refusing to route")
        if (slug == TenantContext.CONTROL) return controlPool()
        return tenantPool(slug)
    }

    private fun controlPool(): HikariDataSource =
        pools.computeIfAbsent(TenantContext.CONTROL) {
            buildPool(props.controlDb, "control")
        }

    private fun tenantPool(slug: String): HikariDataSource =
        pools.computeIfAbsent(slug) {
            val dbName = props.dbPrefix + sanitize(slug)
            if (props.autoCreateDatabase) ensureDatabaseExists(dbName)
            val pool = buildPool(dbName, "tenant-$slug")
            // Materialize the business schema in this fresh tenant database.
            schemaInitializer?.invoke(pool)
            pool
        }

    private fun buildPool(dbName: String, poolLabel: String): HikariDataSource {
        val cfg = HikariConfig().apply {
            jdbcUrl = "jdbc:postgresql://${props.host}/$dbName"
            username = props.username
            password = props.password
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = props.poolSizePerTenant
            minimumIdle = 1
            poolName = "bf-$poolLabel"
            connectionTimeout = 30_000
        }
        log.info("Creating Hikari pool '{}' -> {}", cfg.poolName, dbName)
        return HikariDataSource(cfg)
    }

    /**
     * CREATE DATABASE cannot run inside a transaction nor while connected to the
     * target DB, so we open a one-shot connection to the maintenance database.
     * `CREATE DATABASE IF NOT EXISTS` does not exist in Postgres, so we check
     * pg_database first.
     */
    private fun ensureDatabaseExists(dbName: String) {
        val maintenanceUrl = "jdbc:postgresql://${props.host}/${props.maintenanceDb}"
        java.sql.DriverManager.getConnection(maintenanceUrl, props.username, props.password).use { conn ->
            conn.createStatement().use { st ->
                val rs = st.executeQuery("SELECT 1 FROM pg_database WHERE datname = '$dbName'")
                val exists = rs.next()
                rs.close()
                if (!exists) {
                    log.info("Creating tenant database {}", dbName)
                    // dbName is sanitized to [a-z0-9_]; safe to interpolate.
                    st.executeUpdate("CREATE DATABASE \"$dbName\"")
                }
            }
        }
    }

    /** Allow only safe identifier characters to prevent SQL injection via slug. */
    private fun sanitize(slug: String): String {
        val cleaned = slug.lowercase().filter { it.isLetterOrDigit() || it == '_' }
        require(cleaned.isNotEmpty() && cleaned.length <= 50) { "Invalid tenant slug: $slug" }
        return cleaned
    }

    fun shutdownAll() {
        pools.values.forEach { runCatching { it.close() } }
        pools.clear()
    }
}
