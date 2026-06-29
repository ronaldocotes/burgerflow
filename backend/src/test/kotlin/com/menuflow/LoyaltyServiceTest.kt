package com.menuflow

import com.menuflow.event.OrderPaidEvent
import com.menuflow.exception.ConflictException
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.model.Customer
import com.menuflow.model.LoyaltyReward
import com.menuflow.model.Order
import com.menuflow.model.TenantConfig
import com.menuflow.repository.tenant.CustomerRepository
import com.menuflow.repository.tenant.LoyaltyRewardRepository
import com.menuflow.repository.tenant.LoyaltyTransactionRepository
import com.menuflow.repository.tenant.OrderRepository
import com.menuflow.repository.tenant.TenantConfigRepository
import com.menuflow.service.LoyaltyService
import com.menuflow.service.WhatsAppService
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.UUID

/**
 * Programa de Fidelidade (Fase 3.3) contra um Postgres real (Testcontainers). Prova:
 * crédito de pontos no ORDER_PAID, desbloqueio do punch no limite, programa desligado
 * e pedido anônimo sem crédito, resgate (sucesso/404/409) e ajuste manual (incl. piso 0).
 *
 * Cada caso usa seu PRÓPRIO tenant (db isolado). Não é @Transactional: cada save
 * commita, então o crédito (que roda em transação própria via TransactionTemplate)
 * enxerga a config/cliente já comitados. O WhatsAppService é mockado para não sair
 * chamada HTTP ao WAHA quando uma recompensa é desbloqueada.
 */
class LoyaltyServiceTest @Autowired constructor(
    private val loyaltyService: LoyaltyService,
    private val customerRepository: CustomerRepository,
    private val tenantConfigRepository: TenantConfigRepository,
    private val loyaltyRewardRepository: LoyaltyRewardRepository,
    private val loyaltyTransactionRepository: LoyaltyTransactionRepository,
    private val orderRepository: OrderRepository,
) : IntegrationTestBase() {

    @MockitoBean
    private lateinit var whatsAppService: WhatsAppService

    private lateinit var tenant: String

    /** Matcher any() compatível com parâmetros não-nulos do Kotlin (igual ao PixTest). */
    private fun <T> anyArg(): T = Mockito.any()

    @AfterEach
    fun clear() = TenantContext.clear()

    private fun bind(): String {
        tenant = "loyal_${UUID.randomUUID().toString().take(8)}"
        TenantContext.set(tenant)
        return tenant
    }

    private fun setupLoyalty(enabled: Boolean, pointsPerReal: Int = 1, threshold: Int = 100) {
        TenantContext.set(tenant)
        val config = tenantConfigRepository.findFirstByOrderByCreatedAtAsc() ?: TenantConfig()
        config.loyaltyEnabled = enabled
        config.loyaltyPointsPerReal = pointsPerReal
        config.loyaltyRewardThreshold = threshold
        tenantConfigRepository.save(config)
    }

    private fun newCustomer(): UUID {
        TenantContext.set(tenant)
        val phone = "9199${UUID.randomUUID().toString().filter { it.isDigit() }.take(6)}"
        return customerRepository.save(Customer(name = "Cliente", phoneNumber = phone)).id!!
    }

    /** Persiste um pedido mínimo real (a FK loyalty_transactions.order_id exige um orders.id existente). */
    private fun newOrder(totalCents: Long): UUID {
        TenantContext.set(tenant)
        return orderRepository.save(
            Order(orderNumber = "MF-T-${UUID.randomUUID().toString().take(8)}", totalCents = totalCents),
        ).id!!
    }

    /** Dispara o crédito de fidelidade como o listener faria após o pagamento. */
    private fun pay(customerId: UUID?, totalCents: Long, phone: String? = null) {
        TenantContext.set(tenant)
        val orderId = newOrder(totalCents)
        loyaltyService.applyOrderPaid(OrderPaidEvent(tenant, orderId, customerId, phone, totalCents))
    }

    private fun points(customerId: UUID): Int {
        TenantContext.set(tenant)
        return customerRepository.findById(customerId).get().loyaltyPoints
    }

    private fun punches(customerId: UUID): Long {
        TenantContext.set(tenant)
        return loyaltyRewardRepository.countByCustomerIdAndRedeemedAtIsNull(customerId)
    }

    @Test
    fun `credita pontos apos ORDER_PAID`() {
        bind()
        setupLoyalty(enabled = true, pointsPerReal = 1, threshold = 100)
        val customer = newCustomer()

        pay(customer, totalCents = 5_000) // R$50,00 -> 50 pontos

        assertEquals(50, points(customer))
        assertEquals(0, punches(customer))
    }

    @Test
    fun `desbloqueia punch quando atinge o limite`() {
        bind()
        setupLoyalty(enabled = true, pointsPerReal = 1, threshold = 10)
        val customer = newCustomer()

        pay(customer, totalCents = 1_500) // 15 pontos >= 10 -> 1 punch, sobra 5

        assertEquals(5, points(customer))
        assertEquals(1, punches(customer))
        // WhatsApp de parabéns foi disparado (recompensa desbloqueada).
        Mockito.verify(whatsAppService).sendLoyaltyReward(anyArg(), anyArg())
    }

    @Test
    fun `nao desbloqueia punch abaixo do limite`() {
        bind()
        setupLoyalty(enabled = true, pointsPerReal = 1, threshold = 100)
        val customer = newCustomer()

        pay(customer, totalCents = 5_000) // 50 pontos < 100

        assertEquals(50, points(customer))
        assertEquals(0, punches(customer))
    }

    @Test
    fun `programa desligado nao credita pontos`() {
        bind()
        setupLoyalty(enabled = false)
        val customer = newCustomer()

        pay(customer, totalCents = 5_000)

        assertEquals(0, points(customer))
    }

    @Test
    fun `pedido anonimo (customerId nulo) e ignorado em silencio`() {
        bind()
        setupLoyalty(enabled = true)
        assertDoesNotThrow { pay(customerId = null, totalCents = 9_900) }
    }

    @Test
    fun `total abaixo de um real nao credita`() {
        bind()
        setupLoyalty(enabled = true, pointsPerReal = 1, threshold = 100)
        val customer = newCustomer()

        pay(customer, totalCents = 99) // R$0,99 -> 0 pontos

        assertEquals(0, points(customer))
        assertEquals(0, loyaltyTransactionRepository.findTop10ByCustomerIdOrderByCreatedAtDesc(customer).size)
    }

    @Test
    fun `mesmo pedido nao credita pontos duas vezes (idempotencia)`() {
        bind()
        setupLoyalty(enabled = true, pointsPerReal = 1, threshold = 1000)
        val customer = newCustomer()
        val orderId = newOrder(5_000)

        TenantContext.set(tenant)
        loyaltyService.applyOrderPaid(OrderPaidEvent(tenant, orderId, customer, null, 5_000))
        TenantContext.set(tenant)
        loyaltyService.applyOrderPaid(OrderPaidEvent(tenant, orderId, customer, null, 5_000))

        assertEquals(50, points(customer), "o reenvio do mesmo pedido não credita de novo")
    }

    @Test
    fun `resgate de recompensa sucesso, 404 e 409`() {
        bind()
        setupLoyalty(enabled = true)
        val customer = newCustomer()
        TenantContext.set(tenant)
        val reward = loyaltyRewardRepository.save(LoyaltyReward(customerId = customer))

        // Sucesso.
        TenantContext.set(tenant)
        loyaltyService.redeemReward(customer, reward.id!!)
        TenantContext.set(tenant)
        assertEquals(0, punches(customer), "punch resgatado não conta mais como disponível")

        // Já resgatada -> 409.
        TenantContext.set(tenant)
        assertThrows(ConflictException::class.java) { loyaltyService.redeemReward(customer, reward.id!!) }

        // Inexistente -> 404.
        TenantContext.set(tenant)
        assertThrows(ResourceNotFoundException::class.java) {
            loyaltyService.redeemReward(customer, UUID.randomUUID())
        }
    }

    @Test
    fun `resgate de recompensa de outro cliente retorna 404 (anti-IDOR)`() {
        bind()
        setupLoyalty(enabled = true)
        val customerA = newCustomer()
        val customerB = newCustomer()
        TenantContext.set(tenant)
        val rewardA = loyaltyRewardRepository.save(LoyaltyReward(customerId = customerA))

        // B tenta resgatar a recompensa de A -> 404 (não vaza existência).
        TenantContext.set(tenant)
        assertThrows(ResourceNotFoundException::class.java) {
            loyaltyService.redeemReward(customerB, rewardA.id!!)
        }
    }

    @Test
    fun `ajuste manual soma, subtrai e nao fica negativo`() {
        bind()
        setupLoyalty(enabled = true, threshold = 100)
        val customer = newCustomer()

        TenantContext.set(tenant)
        loyaltyService.adjustPoints(customer, 30, "Cortesia")
        assertEquals(30, points(customer))

        TenantContext.set(tenant)
        loyaltyService.adjustPoints(customer, -10, "Correção")
        assertEquals(20, points(customer))

        // Subtrai mais do que tem -> piso em 0.
        TenantContext.set(tenant)
        loyaltyService.adjustPoints(customer, -999, "Estorno total")
        assertEquals(0, points(customer))
    }
}
