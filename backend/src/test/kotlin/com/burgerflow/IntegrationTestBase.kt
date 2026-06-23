package com.burgerflow

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
                .withDatabaseName("burgerflow_control")
                .withUsername("burgerflow")
                .withPassword("burgerflow123")
                .also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            val hostPort = "${postgres.host}:${postgres.getMappedPort(5432)}"
            registry.add("burgerflow.tenant.host") { hostPort }
            registry.add("burgerflow.tenant.username") { "burgerflow" }
            registry.add("burgerflow.tenant.password") { "burgerflow123" }
            registry.add("burgerflow.tenant.control-db") { "burgerflow_control" }
            registry.add("burgerflow.tenant.maintenance-db") { "burgerflow_control" }
            registry.add("burgerflow.tenant.db-prefix") { "tenant_" }
            registry.add("burgerflow.tenant.auto-create-database") { "true" }
            registry.add("burgerflow.jwt.secret") { "test-secret-key-for-burgerflow-integration-tests-256!" }
        }
    }
}
