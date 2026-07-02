package com.menuflow

import com.fasterxml.jackson.databind.ObjectMapper
import com.menuflow.event.OrderPaidEvent
import com.menuflow.model.Customer
import com.menuflow.model.TenantConfig
import com.menuflow.model.control.Tenant
import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.tenant.CustomerRepository
import com.menuflow.repository.tenant.LoyaltyRewardRepository
import com.menuflow.repository.tenant.OrderRepository
import com.menuflow.repository.tenant.TenantConfigRepository
import com.menuflow.service.LoyaltyService
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Sumário gerencial de fidelidade (GET /loyalty/summary) e configuração pública
 * (GET /public/{slug}/loyalty-config). Testcontainers + Postgres real.
 *
 * Cada caso usa tenant isolado. Não é @Transactional: os saves commitam para que
 * o getSummary() enxergue os dados (mesmo padrão do LoyaltyServiceTest).
 */
@AutoConfigureMockMvc
class LoyaltySummaryTest @Autowired constructor(
    private val loyaltyService: LoyaltyService,
    private val customerRepository: CustomerRepository,
    private val tenantConfigRepository: TenantConfigRepository,
    private val loyaltyRewardRepository: LoyaltyRewardRepository,
    private val orderRepository: OrderRepository,
    private val tenantRepository: TenantRepository,
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
) : IntegrationTestBase() {

    @MockitoBean
    private lateinit var whatsAppService: com.menuflow.service.WhatsAppService

    private lateinit var tenant: String

    @AfterEach
    fun clear() = TenantContext.clear()

    private fun bind(prefix: String = "lsum"): String {
        tenant = "${prefix}_${UUID.randomUUID().toString().take(8)}"
        TenantContext.set(tenant)
        return tenant
    }

    private fun setupLoyalty(enabled: Boolean = true, pointsPerReal: Int = 1, threshold: Int = 100) {
        TenantContext.set(tenant)
        val config = tenantConfigRepository.findFirstByOrderByCreatedAtAsc() ?: TenantConfig()
        config.loyaltyEnabled = enabled
        config.loyaltyPointsPerReal = pointsPerReal
        config.loyaltyRewardThreshold = threshold
        config.loyaltyRewardDescription = "Hamburguer gratis!"
        tenantConfigRepository.save(config)
    }

    private fun newCustomer(): UUID {
        TenantContext.set(tenant)
        val phone = "9199${UUID.randomUUID().toString().filter { it.isDigit() }.take(6)}"
        return customerRepository.save(Customer(name = "Cliente", phoneNumber = phone)).id!!
    }

    private fun newOrder(totalCents: Long): UUID {
        TenantContext.set(tenant)
        return orderRepository.save(
            com.menuflow.model.Order(
                orderNumber = "MF-S-${UUID.randomUUID().toString().take(8)}",
                totalCents = totalCents,
            ),
        ).id!!
    }

    /** Dispara o credito de fidelidade como o listener faria apos o pagamento. */
    private fun pay(customerId: UUID, totalCents: Long) {
        TenantContext.set(tenant)
        val orderId = newOrder(totalCents)
        loyaltyService.applyOrderPaid(OrderPaidEvent(tenant, orderId, customerId, null, totalCents))
    }

    // --- Testes de getSummary ---

    @Test
    fun `sumario conta clientes com pontos, pontos emitidos e recompensas resgatadas no periodo`() {
        bind()
        setupLoyalty(enabled = true, pointsPerReal = 1, threshold = 10)
        val customerA = newCustomer()
        val customerB = newCustomer()

        // customerA: 2 pedidos, total = 15 pontos (1 punch de 10 + sobra 5)
        pay(customerA, 1_000L) // R$10 -> 10 pontos -> desbloqueia punch
        pay(customerA, 500L)   // R$5  ->  5 pontos

        // customerB: 1 pedido (nao atinge threshold)
        pay(customerB, 300L)   // R$3  ->  3 pontos

        // Resgata o punch de customerA
        TenantContext.set(tenant)
        val reward = loyaltyRewardRepository.findFirstByCustomerIdAndRedeemedAtIsNull(customerA)!!
        loyaltyService.redeemReward(customerA, reward.id!!)

        val today = LocalDate.now(ZoneId.of("America/Sao_Paulo"))
        TenantContext.set(tenant)
        val summary = loyaltyService.getSummary(today, today)

        // 2 clientes com pontos > 0 (customerA tem 5 sobra, customerB tem 3)
        assertEquals(2L, summary.activeCustomers, "dois clientes com pontos devem ser contados")
        // Pontos emitidos: 10 + 5 + 3 = 18 creditos de ORDER_PAID
        assertEquals(18L, summary.totalPointsIssued, "soma de todos os creditos do periodo")
        // 1 recompensa resgatada
        assertEquals(1L, summary.totalRewardsRedeemed, "um punch foi resgatado no periodo")
        // Pontos debitados por resgate: 10 (o threshold)
        assertEquals(10L, summary.totalPointsRedeemed, "pontos gastos no resgate")
    }

    @Test
    fun `sumario fora do periodo devolve zeros (exceto activeCustomers que e snapshot atual)`() {
        bind()
        setupLoyalty(enabled = true, pointsPerReal = 1, threshold = 100)
        val customer = newCustomer()

        pay(customer, 5_000L) // Agora

        // Periodo no passado que nao inclui o evento de agora
        val pastStart = LocalDate.of(2020, 1, 1)
        val pastEnd = LocalDate.of(2020, 1, 31)

        TenantContext.set(tenant)
        val summary = loyaltyService.getSummary(pastStart, pastEnd)

        // activeCustomers e snapshot atual (nao filtrado por periodo): tem 1 cliente com pontos
        assertEquals(1L, summary.activeCustomers, "snapshot atual deve refletir o saldo existente")
        // Nada foi emitido nem resgatado no periodo passado
        assertEquals(0L, summary.totalPointsIssued, "nenhum ponto emitido no periodo passado")
        assertEquals(0L, summary.totalRewardsRedeemed, "nenhuma recompensa no periodo passado")
        assertEquals(0L, summary.totalPointsRedeemed, "nenhum ponto gasto no periodo passado")
    }

    @Test
    fun `sumario com programa desligado nao credita pontos e sumario retorna zeros`() {
        bind()
        setupLoyalty(enabled = false)
        val customer = newCustomer()

        pay(customer, 5_000L) // Programa off: nao credita

        val today = LocalDate.now(ZoneId.of("America/Sao_Paulo"))
        TenantContext.set(tenant)
        val summary = loyaltyService.getSummary(today, today)

        assertEquals(0L, summary.activeCustomers, "programa off: cliente nao tem pontos")
        assertEquals(0L, summary.totalPointsIssued)
        assertEquals(0L, summary.totalRewardsRedeemed)
        assertEquals(0L, summary.totalPointsRedeemed)
    }

    // --- Testes do endpoint publico GET /public/{slug}/loyalty-config ---

    @Test
    fun `loyalty-config publico reflete a config do tenant`() {
        val slug = bind("lcfg")
        // Precisa de um Tenant no banco de CONTROLE para o endpoint nao retornar 404.
        tenantRepository.save(Tenant(slug = slug, displayName = "Leal Burguer"))
        setupLoyalty(enabled = true, pointsPerReal = 2, threshold = 50)
        // Atualizar descricao da recompensa via config (ja salvo no setupLoyalty)

        mockMvc.perform(get("/public/$slug/loyalty-config"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.pointsPerReal").value(2))
            .andExpect(jsonPath("$.rewardThreshold").value(50))
            .andExpect(jsonPath("$.rewardDescription").value("Hamburguer gratis!"))
    }

    @Test
    fun `loyalty-config publico com programa desligado retorna enabled false`() {
        val slug = bind("lcfg2")
        tenantRepository.save(Tenant(slug = slug, displayName = "Leal Burguer 2"))
        setupLoyalty(enabled = false, pointsPerReal = 1, threshold = 100)

        mockMvc.perform(get("/public/$slug/loyalty-config"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.enabled").value(false))
    }

    @Test
    fun `loyalty-config publico com tenant inexistente retorna 404`() {
        mockMvc.perform(get("/public/tenant-nao-existe-xyz/loyalty-config"))
            .andExpect(status().isNotFound)
    }
}
