package com.menuflow.tenant

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Connection template used to build per-tenant datasources.
 *
 * The same Postgres server/credentials host every tenant database; only the
 * database name changes (`<dbPrefix><slug>`). In production each tenant could
 * point at a different host — this template is the single place to evolve that.
 */
@ConfigurationProperties(prefix = "menuflow.tenant")
data class TenantDataSourceProperties(
    /** JDBC host:port, e.g. localhost:5432 */
    val host: String = "localhost:5432",
    val username: String = "menuflow",
    val password: String = "menuflow123",
    /** Physical database name prefix; final name is `<dbPrefix><slug>`. */
    val dbPrefix: String = "tenant_",
    /** Database used to issue CREATE DATABASE (cannot run inside the target db). */
    val maintenanceDb: String = "postgres",
    /** Control database name (tenant + user registry / auth). */
    val controlDb: String = "menuflow_control",
    /**
     * Pool size PER TENANT. Kept small on purpose: with many tenants the sum of
     * pools must stay under Postgres max_connections. See conhecimento Seç.6.
     */
    val poolSizePerTenant: Int = 5,
    /** Auto-create the tenant database on first access if it does not exist. */
    val autoCreateDatabase: Boolean = true,
)
