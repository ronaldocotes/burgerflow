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
            registry.add("menuflow.jwt.secret") { "test-secret-key-for-menuflow-integration-tests-256!" }
            // NOTE: login rate-limit is disabled by default for tests via
            // src/test/resources/application.yml (MockMvc always reports
            // 127.0.0.1, so a shared bucket would trip across the suite). The
            // dedicated LoginRateLimitTest re-enables it through
            // @DynamicPropertySource, which overrides the YAML default.
        }
    }
}
