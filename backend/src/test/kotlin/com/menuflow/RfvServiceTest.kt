package com.menuflow

import com.menuflow.model.Customer
import com.menuflow.model.Order
import com.menuflow.model.RfvSegment
import com.menuflow.repository.tenant.CustomerRepository
import com.menuflow.repository.tenant.OrderRepository
import com.menuflow.service.RfvService
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Testes de RfvService (Fase 3.4).
 *
 * Casos de classify (funcao pura, sem banco):
 *  - freq90>=5 + lifetimeOrders>=10 -> LOYAL  (variante de alta frequencia)
 *  - recenciaDias>45                -> INACTIVE
 *
 * Casos de integracao (Testcontainers, banco real):
 *  - scoreAll() sem nenhum pedido  -> lista vazia, sem NPE
 *  - scoreAll() cliente inativo    -> segmento INACTIVE, phoneNumber preenchido
 *
 * OBS: casos basicos de classify (NEW/INACTIVE/LOYAL/AT_RISK) ja estao
 * cobertos em CampaignServiceTest; este arquivo foca nas variantes adicionais.
 */
class RfvServiceTest @Autowired constructor(
    private val rfvService: RfvService,
    private val customerRepository: CustomerRepository,
    private val orderRepository: OrderRepository,
) : IntegrationTestBase() {

    private lateinit var tenant: String

    @BeforeEach
    fun setup() {
        tenant = "rfv_${UUID.randomUUID().toString().take(8)}"
        TenantContext.set(tenant)
    }

    @AfterEach
    fun clear() = TenantContext.clear()

    // ---- classify: funcao pura, sem banco ----

    @Test
    fun `classify retorna LOYAL para cliente com freq90 alto e muitos pedidos na vida`() {
        val result = RfvService.classify(recencyDays = 7, freq90 = 5, lifetimeOrders = 10)
        assertEquals(RfvSegment.LOYAL, result, "cliente ativo (7 dias) e frequente (5/90d) deve ser LOYAL")
    }

    @Test
    fun `classify retorna INACTIVE para cliente sem pedidos recentes`() {
        val result = RfvService.classify(recencyDays = 50, freq90 = 0, lifetimeOrders = 5)
        assertEquals(RfvSegment.INACTIVE, result, "ultimo pedido ha 50 dias deve ser INACTIVE")
    }

    // ---- scoreAll: integracao com banco ----

    @Test
    fun `scoreAll retorna lista vazia quando nenhum pedido existe no tenant`() {
        TenantContext.set(tenant)
        val scores = rfvService.scoreAll()
        assertTrue(scores.isEmpty(), "tenant sem pedidos deve retornar lista vazia sem NPE")
    }

    @Test
    fun `scoreAll classifica INACTIVE e preenche phoneNumber`() {
        TenantContext.set(tenant)
        val phone = "9${UUID.randomUUID().toString().filter { it.isDigit() }.take(9)}"
        val customer = customerRepository.save(
            Customer(name = "Sumido", phoneNumber = phone, loyaltyPoints = 0, marketingOptIn = false),
        )

        // 2 pedidos antigos (fora da janela de recencia de 45 dias)
        repeat(2) { i ->
            orderRepository.save(
                Order(
                    orderNumber = "MF-INACT-${UUID.randomUUID().toString().take(8)}",
                    customerId = customer.id,
                    totalCents = 2000,
                    createdAt = Instant.now().minus(50 + i.toLong(), ChronoUnit.DAYS),
                ),
            )
        }

        TenantContext.set(tenant)
        val score = rfvService.scoreAll().firstOrNull { it.customerId == customer.id }
        assertEquals(RfvSegment.INACTIVE, score?.segment, "cliente sumido (50+ dias) deve ser INACTIVE")
        assertEquals(phone, score?.phoneNumber, "phoneNumber deve ser propagado do Customer para o score")
    }
}
