package com.menuflow.tenant

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource
import org.springframework.scheduling.annotation.Scheduled
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
     * business schema in that tenant database. Receives the tenant slug and the
     * freshly built DataSource. In production this is TenantFlywayMigrator.migrate:
     * it runs the versioned tenant migrations and records the result in the
     * control DB ledger. Null for the control pool.
     */
    @Volatile var schemaInitializer: ((String, DataSource) -> Unit)? = null,
) : AbstractRoutingDataSource(), DisposableBean {

    private val log = LoggerFactory.getLogger(javaClass)
    private val pools = ConcurrentHashMap<String, HikariDataSource>()

    /**
     * Último acesso (epoch millis) por slug de tenant, para a evicção de pools
     * ociosos. O pool de CONTROLE não entra aqui (nunca é evictado).
     */
    private val lastAccessMillis = ConcurrentHashMap<String, Long>()

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

    private fun tenantPool(slug: String): HikariDataSource {
        lastAccessMillis[slug] = System.currentTimeMillis()
        return pools.computeIfAbsent(slug) {
            val dbName = props.dbPrefix + sanitize(slug)
            if (props.autoCreateDatabase) ensureDatabaseExists(dbName)
            val pool = buildPool(dbName, "tenant-$slug")
            // Materialize the business schema in this fresh tenant database via
            // Flyway (idempotent: a no-op if the tenant DB is already at HEAD).
            schemaInitializer?.invoke(slug, pool)
            pool
        }
    }

    private fun buildPool(dbName: String, poolLabel: String): HikariDataSource {
        val cfg = HikariConfig().apply {
            jdbcUrl = "jdbc:postgresql://${props.host}/$dbName"
            username = props.username
            password = props.password
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = props.poolSizePerTenant
            // Ocioso encolhe até minIdlePerTenant (padrão 0): um tenant sem tráfego
            // não segura conexão. Com dezenas de tenants isso derruba o pico total.
            minimumIdle = props.minIdlePerTenant.coerceIn(0, props.poolSizePerTenant)
            // Só faz efeito quando minIdle < maxPool. Hikari exige >= 10000.
            idleTimeout = props.idlePoolIdleTimeoutMs.coerceAtLeast(10_000)
            poolName = "menuflow-$poolLabel"
            connectionTimeout = 30_000
        }
        log.info(
            "Creating Hikari pool '{}' -> {} (maxPool={}, minIdle={})",
            cfg.poolName, dbName, cfg.maximumPoolSize, cfg.minimumIdle,
        )
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
        lastAccessMillis.clear()
    }

    /**
     * Fecha TODOS os pools de tenant/controle quando o contexto Spring é descartado.
     * Esta é a RAIZ da issue #33: os HikariDataSource são criados à mão dentro deste
     * bean e o Spring não os conhece, então sem este destroy() as conexões vazavam a
     * cada contexto de teste descartado (a suíte sobe vários contextos na mesma JVM),
     * acumulando até o Postgres recusar conexões. Beans retornados de @Bean têm
     * DisposableBean.destroy() invocado no shutdown do contexto.
     */
    override fun destroy() {
        log.info("Closing {} tenant/control pool(s) on context shutdown", pools.size)
        shutdownAll()
    }

    /**
     * Closes and forgets the pool for [slug] so the NEXT access rebuilds it (and
     * re-runs the Flyway migrator against the existing tenant DB — idempotent).
     * Used to recycle a tenant pool and exercised by the migration tests. The
     * tenant database itself is left intact.
     */
    fun evictPool(slug: String) {
        pools.remove(slug)?.let { runCatching { it.close() } }
        lastAccessMillis.remove(slug)
    }

    /**
     * Evicção de pools de tenant ociosos (produção): fecha por inteiro o pool de
     * qualquer tenant sem acesso há mais de [props.idlePoolEvictionMinutes]. Roda a
     * cada 5 min; no-op quando a evicção está desligada (minutes <= 0), que é o
     * padrão em dev/teste — assim não interfere na suíte. O pool de CONTROLE nunca
     * é evictado. Retorna quantos pools fechou (útil para teste). Reabre sob demanda.
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000L)
    fun evictIdleTenantPools(): Int {
        val minutes = props.idlePoolEvictionMinutes
        if (minutes <= 0) return 0
        return evictTenantPoolsIdleFor(minutes * 60_000L)
    }

    /**
     * Fecha os pools de tenant (nunca o de CONTROLE) sem acesso há mais de
     * [maxIdleMillis]. Separado de [evictIdleTenantPools] para ser exercido por teste
     * com um limite explícito sem depender do relógio do agendador.
     */
    fun evictTenantPoolsIdleFor(maxIdleMillis: Long): Int {
        val cutoff = System.currentTimeMillis() - maxIdleMillis
        var evicted = 0
        pools.keys
            .filter { it != TenantContext.CONTROL && (lastAccessMillis[it] ?: 0L) <= cutoff }
            .forEach { slug ->
                evictPool(slug)
                evicted++
                log.info("Evicted idle tenant pool '{}'", slug)
            }
        return evicted
    }

    /** Nº de pools atualmente abertos (não fechados). Para asserção de teste/métrica. */
    fun openPoolCount(): Int = pools.values.count { !it.isClosed }

    /** Acesso somente-teste ao pool de um slug, para verificar isClosed após destroy. */
    internal fun poolForTest(slug: String): HikariDataSource? = pools[slug]
}
