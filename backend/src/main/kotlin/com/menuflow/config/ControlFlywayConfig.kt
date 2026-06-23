package com.menuflow.config

import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

/**
 * Flyway for the CONTROL database.
 *
 * Spring Boot's FlywayAutoConfiguration is NOT used here: DataSourceAutoConfiguration
 * is excluded (database-per-tenant — see MenuFlowApplication), so there is no
 * single auto-configured datasource for Flyway to latch onto, and we must never
 * let Flyway run against the per-tenant ROUTING datasource (that is driven only
 * by TenantFlywayMigrator, lazily, per tenant). So we run control Flyway by hand,
 * bound explicitly to the control pool.
 *
 * The `flywayControl` bean is depended upon by the control EntityManagerFactory
 * (see ControlDataSourceConfig: @DependsOn("flywayControl")) so the schema is at
 * HEAD before Hibernate runs its `validate`.
 */
@Configuration
class ControlFlywayConfig {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean(name = ["flywayControl"], initMethod = "migrate")
    fun flywayControl(@Qualifier("controlDataSource") controlDataSource: DataSource): Flyway {
        log.info("Configuring Flyway for the CONTROL database")
        return Flyway.configure()
            .dataSource(controlDataSource)
            .locations("classpath:db/control/migration")
            // Default history table name (flyway_schema_history) is fine for the
            // control DB; tenant DBs use a distinct `schema_version` table so the
            // two never collide on a shared Postgres server.
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load()
    }
}
