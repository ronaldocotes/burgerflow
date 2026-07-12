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
    /**
     * Conexões ociosas MANTIDAS por pool de tenant. Padrão 0: um tenant que parou
     * de receber tráfego deixa seu pool encolher a ZERO conexões (Hikari fecha as
     * ociosas após [idlePoolIdleTimeoutMs]). Com dezenas de tenants isso derruba o
     * pico de conexões: só quem está ativo segura conexão. minimum-idle=1 (antigo)
     * segurava 1 conexão por tenant indefinidamente e ajudava a esgotar o Postgres.
     */
    val minIdlePerTenant: Int = 0,
    /**
     * Quanto tempo (ms) uma conexão pode ficar ociosa ACIMA de [minIdlePerTenant]
     * antes de o Hikari fechá-la. Só tem efeito quando minIdle < maximumPoolSize.
     * Mínimo aceito pelo Hikari é 10000.
     */
    val idlePoolIdleTimeoutMs: Long = 60_000,
    /**
     * Evicção de POOLS de tenant ociosos (produção): fecha por inteiro o pool de um
     * tenant sem nenhum acesso há mais de N minutos, para que a contagem de pools
     * não cresça indefinidamente à medida que tenants entram. 0 = desligado. O
     * pool é recriado sob demanda no próximo acesso ([tenantPool] reexecuta o
     * Flyway, idempotente). O pool de CONTROLE nunca é evictado.
     */
    val idlePoolEvictionMinutes: Long = 0,
    /** Auto-create the tenant database on first access if it does not exist. */
    val autoCreateDatabase: Boolean = true,
)
