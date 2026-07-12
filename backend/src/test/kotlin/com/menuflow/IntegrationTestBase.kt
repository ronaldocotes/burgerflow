package com.menuflow

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Singleton-container pattern: ONE Postgres is started for the whole JVM and
 * shared by every test class. This matters because Spring caches the application
 * context (and therefore the datasources/ports) across test classes; a
 * per-class @Container would change the port under a cached context and cause
 * "connection refused". The container is never stopped explicitly — Ryuk reaps
 * it when the JVM exits.
 *
 * The container's default database doubles as the CONTROL database; per-tenant
 * databases are created on demand by DynamicTenantRoutingDataSource against the
 * same server (the test Postgres user is a superuser, so CREATE DATABASE works).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TenantTestTx::class)
abstract class IntegrationTestBase {

    companion object {
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("menuflow_control")
                .withUsername("menuflow")
                .withPassword("menuflow123")
                // issue #33 RESOLVIDA na raiz: DynamicTenantRoutingDataSource agora fecha seus
                // pools no destroy() do contexto (DisposableBean) E os pools de tenant ociosos
                // encolhem a ZERO conexões (minimum-idle=0 + idle-timeout curto). Antes, cada
                // pool acumulado segurava 1 conexão idle indefinidamente e nada os fechava, então
                // a suíte completa (~52 classes na mesma JVM) estourava mesmo com 500. Com o fix,
                // 500 volta a ter folga larga (o paliativo do PR #34 que subiu p/ 2000 fica
                // desnecessário). O TenantPoolLifecycleTest trava a regressão.
                .withCommand("postgres", "-c", "max_connections=500")
                .also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            val hostPort = "${postgres.host}:${postgres.getMappedPort(5432)}"
            registry.add("menuflow.tenant.host") { hostPort }
            registry.add("menuflow.tenant.username") { "menuflow" }
            registry.add("menuflow.tenant.password") { "menuflow123" }
            registry.add("menuflow.tenant.control-db") { "menuflow_control" }
            registry.add("menuflow.tenant.maintenance-db") { "menuflow_control" }
            registry.add("menuflow.tenant.db-prefix") { "tenant_" }
            registry.add("menuflow.tenant.auto-create-database") { "true" }
            // Pool pequeno por tenant nos testes: a suite provisiona muitos tenants
            // num unico Postgres compartilhado; o tamanho padrao esgotaria max_connections.
            registry.add("menuflow.tenant.pool-size-per-tenant") { "2" }
            // Pools de tenant ociosos drenam a ZERO conexões e rápido na suíte: min-idle 0
            // e idle-timeout no mínimo do Hikari (10s), para o pico total não acumular à
            // medida que dezenas de classes provisionam tenants na mesma JVM (issue #33).
            registry.add("menuflow.tenant.min-idle-per-tenant") { "0" }
            registry.add("menuflow.tenant.idle-pool-idle-timeout-ms") { "10000" }
            registry.add("menuflow.jwt.secret") { "test-secret-key-for-menuflow-integration-tests-256!" }
            // NOTE: login rate-limit is disabled by default for tests via
            // src/test/resources/application.yml (MockMvc always reports
            // 127.0.0.1, so a shared bucket would trip across the suite). The
            // dedicated LoginRateLimitTest re-enables it through
            // @DynamicPropertySource, which overrides the YAML default.
        }
    }
}
