package com.menuflow

import com.menuflow.event.OrderPaidEvent
import com.menuflow.model.CartSession
import com.menuflow.model.CartSessionStatus
import com.menuflow.model.Order
import com.menuflow.model.PaymentStatus
import com.menuflow.model.TenantConfig
import com.menuflow.repository.tenant.CartSessionRepository
import com.menuflow.repository.tenant.OrderRepository
import com.menuflow.repository.tenant.TenantConfigRepository
import com.menuflow.service.CartRecoveryService
import com.menuflow.service.WhatsAppService
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Recuperacao de carrinho abandonado (Fase 3.5) contra um Postgres real (Testcontainers).
 * Prova: criacao da comanda (com/sem telefone), pagamento -> RECOVERED, atraso nao
 * atingido (nada enviado), atraso atingido + telefone (SENT), prazo de expiracao
 * atingido (EXPIRED) e o liga/desliga (enabled=false -> nada).
 *
 * Cada caso usa seu PROPRIO tenant (db isolado). Nao e @Transactional: cada save
 * commita, entao o que roda em transacao propria (TransactionTemplate) enxerga o que
 * foi semeado. O WhatsAppService e mockado (sem chamada HTTP real ao WAHA). Os delays
 * de envio estao em 0 no application.yml de teste (sem espera).
 */
class CartRecoveryServiceTest @Autowired constructor(
    private val cartRecoveryService: CartRecoveryService,
    private val cartSessionRepository: CartSessionRepository,
    private val tenantConfigRepository: TenantConfigRepository,
    private val orderRepository: OrderRepository,
) : IntegrationTestBase() {

    @MockitoBean
    private lateinit var whatsAppService: WhatsAppService

    private lateinit var tenant: String

    /** Matcher any() compativel com parametros nao-nulos do Kotlin. */
    private fun <T> anyArg(): T = Mockito.any()

    @AfterEach
    fun clear() = TenantContext.clear()

    private fun bind(): String {
        tenant = "cart_${UUID.randomUUID().toString().take(8)}"
        TenantContext.set(tenant)
        return tenant
    }

    private fun setupConfig(enabled: Boolean, delayMinutes: Int = 30, expiryHours: Int = 2) {
        TenantContext.set(tenant)
        val config = tenantConfigRepository.findFirstByOrderByCreatedAtAsc() ?: TenantConfig()
        config.cartRecoveryEnabled = enabled
        config.cartRecoveryDelayMinutes = delayMinutes
        config.cartRecoveryExpiryHours = expiryHours
        tenantConfigRepository.save(config)
    }

    /** Persiste um pedido minimo real (a FK cart_sessions.order_id exige orders.id). */
    private fun newOrder(
        phone: String? = "11999990000",
        totalCents: Long = 5_000,
        paymentStatus: PaymentStatus = PaymentStatus.PENDING,
    ): Order {
        TenantContext.set(tenant)
        return orderRepository.save(
            Order(
                orderNumber = "MF-T-${UUID.randomUUID().toString().take(8)}",
                customerPhone = phone,
                totalCents = totalCents,
                paymentStatus = paymentStatus,
            ),
        )
    }

    /** Semeia uma CartSession ACTIVE com createdAt arbitrario (backdate para o tick). */
    private fun seedCart(orderId: UUID, phone: String?, createdAt: Instant): CartSession {
        TenantContext.set(tenant)
        return cartSessionRepository.save(
            CartSession(
                orderId = orderId,
                customerPhone = phone,
                totalCents = 5_000,
                status = CartSessionStatus.ACTIVE,
                createdAt = createdAt,
            ),
        )
    }

    private fun statusOf(orderId: UUID): CartSessionStatus? {
        TenantContext.set(tenant)
        return cartSessionRepository.findByOrderId(orderId)?.status
    }

    @Test
    fun `pedido pendente com telefone cria CartSession ACTIVE`() {
        bind()
        val order = newOrder(phone = "11988887777")

        cartRecoveryService.onOrderCreated(order)

        assertEquals(CartSessionStatus.ACTIVE, statusOf(order.id!!))
    }

    @Test
    fun `pedido pendente sem telefone nao cria CartSession`() {
        bind()
        val order = newOrder(phone = null)

        cartRecoveryService.onOrderCreated(order)

        TenantContext.set(tenant)
        assertNull(cartSessionRepository.findByOrderId(order.id!!))
    }

    @Test
    fun `pedido ja pago nao cria CartSession`() {
        bind()
        val order = newOrder(phone = "11988887777", paymentStatus = PaymentStatus.PAID)

        cartRecoveryService.onOrderCreated(order)

        TenantContext.set(tenant)
        assertNull(cartSessionRepository.findByOrderId(order.id!!))
    }

    @Test
    fun `pagamento marca a comanda como RECOVERED`() {
        bind()
        val order = newOrder()
        seedCart(order.id!!, "11988887777", Instant.now())

        TenantContext.set(tenant)
        cartRecoveryService.applyOrderPaid(OrderPaidEvent(tenant, order.id!!, null, "11988887777", 5_000))

        assertEquals(CartSessionStatus.RECOVERED, statusOf(order.id!!))
    }

    @Test
    fun `atraso nao atingido nao envia mensagem`() {
        bind()
        setupConfig(enabled = true, delayMinutes = 30, expiryHours = 2)
        val order = newOrder()
        seedCart(order.id!!, "11988887777", Instant.now()) // recem-criada

        val sent = cartRecoveryService.processAbandonedCarts(tenant)

        assertEquals(0, sent)
        assertEquals(CartSessionStatus.ACTIVE, statusOf(order.id!!))
        Mockito.verify(whatsAppService, Mockito.never()).sendCampaign(anyArg(), anyArg(), anyArg())
    }

    @Test
    fun `atraso atingido com telefone envia e marca SENT`() {
        bind()
        setupConfig(enabled = true, delayMinutes = 30, expiryHours = 2)
        Mockito.`when`(whatsAppService.sendCampaign(anyArg(), anyArg(), anyArg())).thenReturn(true)
        val order = newOrder()
        // criado ha 40min: passou o atraso (30) e ainda dentro do prazo (2h).
        seedCart(order.id!!, "11988887777", Instant.now().minus(40, ChronoUnit.MINUTES))

        val sent = cartRecoveryService.processAbandonedCarts(tenant)

        assertEquals(1, sent)
        assertEquals(CartSessionStatus.SENT, statusOf(order.id!!))
        Mockito.verify(whatsAppService).sendCampaign(anyArg(), anyArg(), anyArg())
    }

    @Test
    fun `prazo de expiracao atingido marca EXPIRED sem enviar`() {
        bind()
        setupConfig(enabled = true, delayMinutes = 30, expiryHours = 2)
        val order = newOrder()
        // criado ha 3h: passou o atraso E o prazo de expiracao (2h) -> EXPIRED.
        seedCart(order.id!!, "11988887777", Instant.now().minus(3, ChronoUnit.HOURS))

        val sent = cartRecoveryService.processAbandonedCarts(tenant)

        assertEquals(0, sent)
        assertEquals(CartSessionStatus.EXPIRED, statusOf(order.id!!))
        Mockito.verify(whatsAppService, Mockito.never()).sendCampaign(anyArg(), anyArg(), anyArg())
    }

    @Test
    fun `recuperacao desligada nao faz nada`() {
        bind()
        setupConfig(enabled = false)
        val order = newOrder()
        seedCart(order.id!!, "11988887777", Instant.now().minus(40, ChronoUnit.MINUTES))

        val sent = cartRecoveryService.processAbandonedCarts(tenant)

        assertEquals(0, sent)
        assertEquals(CartSessionStatus.ACTIVE, statusOf(order.id!!))
        Mockito.verify(whatsAppService, Mockito.never()).sendCampaign(anyArg(), anyArg(), anyArg())
    }
}
