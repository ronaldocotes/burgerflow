package com.menuflow

import com.menuflow.tenant.DynamicTenantRoutingDataSource
import com.menuflow.tenant.TenantContext
import com.menuflow.tenant.TenantDataSourceProperties
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Trava a regressão da issue #33: os pools de tenant do
 * [DynamicTenantRoutingDataSource] devem ser FECHADOS quando o bean é destruído
 * (fim do contexto Spring) e devem poder ser evictados quando ociosos. Sem isso,
 * cada contexto de teste descartado vazava poolSizePerTenant × N_tenants conexões,
 * acumulando na mesma JVM até o Postgres recusar conexões.
 *
 * Construímos uma instância PRÓPRIA do routing datasource (não a bean compartilhada
 * da suíte) apontada para o mesmo Postgres do Testcontainer, para poder chamar
 * destroy() sem afetar os outros testes. schemaInitializer = null: só cria o banco
 * e o pool (sem rodar Flyway), o que basta para exercitar o ciclo de vida do pool.
 */
class TenantPoolLifecycleTest @Autowired constructor(
    private val props: TenantDataSourceProperties,
) : IntegrationTestBase() {

    @AfterEach
    fun clear() = TenantContext.clear()

    /** Abre e fecha uma conexão roteada para materializar o pool do tenant. */
    private fun DynamicTenantRoutingDataSource.provision(slug: String) {
        TenantContext.set(slug)
        connection.use { /* primeiro acesso cria o banco + o pool */ }
        TenantContext.clear()
    }

    @Test
    fun `destroy fecha todos os pools de tenant (fix issue 33)`() {
        val routing = DynamicTenantRoutingDataSource(props) // schemaInitializer = null
        val slug = "poollifecycle1"

        routing.provision(slug)
        val pool = routing.poolForTest(slug)
        requireNotNull(pool) { "o pool do tenant deveria existir após o acesso" }
        assertFalse(pool.isClosed, "pool deve estar aberto após provisionar")
        assertTrue(routing.openPoolCount() >= 2, "controle + tenant abertos")

        routing.destroy()

        assertTrue(pool.isClosed, "destroy() deve fechar o HikariDataSource do tenant")
        assertEquals(0, routing.openPoolCount(), "nenhum pool deve seguir aberto após destroy()")
    }

    @Test
    fun `evicta pool de tenant ocioso mas preserva o de controle`() {
        val routing = DynamicTenantRoutingDataSource(props)
        try {
            val slug = "poollifecycle2"
            routing.provision(slug)
            val tenantPool = routing.poolForTest(slug)
            requireNotNull(tenantPool)

            // Limite 0ms: tudo que foi acessado no passado (>= agora) é considerado ocioso.
            val evicted = routing.evictTenantPoolsIdleFor(0L)

            assertEquals(1, evicted, "deve evictar exatamente o pool do tenant ocioso")
            assertTrue(tenantPool.isClosed, "pool de tenant evictado deve estar fechado")
            // Controle preservado e utilizável (routing reabre o pool do tenant sob demanda).
            val control = routing.poolForTest(TenantContext.CONTROL)
            requireNotNull(control) { "o pool de controle nunca deve ser evictado" }
            assertFalse(control.isClosed, "o pool de CONTROLE não pode ser evictado")

            // Reabre sob demanda no próximo acesso.
            routing.provision(slug)
            assertFalse(routing.poolForTest(slug)!!.isClosed, "pool do tenant deve reabrir sob demanda")
        } finally {
            routing.destroy()
        }
    }
}
