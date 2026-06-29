package com.menuflow

import com.menuflow.event.OrderPaidEvent
import com.menuflow.model.ConversionDispatch
import com.menuflow.model.ConversionPlatform
import com.menuflow.model.ConversionStatus
import com.menuflow.model.Order
import com.menuflow.model.TenantConfig
import com.menuflow.repository.tenant.ConversionDispatchRepository
import com.menuflow.repository.tenant.OrderRepository
import com.menuflow.repository.tenant.TenantConfigRepository
import com.menuflow.service.ConversionService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.security.MessageDigest
import java.util.UUID

/**
 * Rastreamento de conversao (Fase 3.7) contra um Postgres real (Testcontainers).
 * Prova: agendamento cria despachos PENDING conforme a config (Meta/Google/ambos/
 * desligado), idempotencia por (order_id, platform), hashing de PII (telefone) e a
 * transicao FAILED->SKIPPED apos o limite de tentativas. Nenhuma chamada HTTP real:
 * os casos que tocariam Meta/Google nao sao exercitados (scheduleConversions so cria
 * PENDING; o caso de retry usa attempts=3, que pula antes de qualquer envio).
 *
 * Cada caso usa seu PROPRIO tenant (db isolado). Nao e @Transactional: cada save
 * commita, entao o que roda em transacao propria (TransactionTemplate) enxerga o
 * que foi semeado.
 */
class ConversionServiceTest @Autowired constructor(
    private val conversionService: ConversionService,
    private val conversionDispatchRepository: ConversionDispatchRepository,
    private val tenantConfigRepository: TenantConfigRepository,
    private val orderRepository: OrderRepository,
) : IntegrationTestBase() {

    private lateinit var tenant: String

    @AfterEach
    fun clear() = com.menuflow.tenant.TenantContext.clear()

    private fun bind(): String {
        tenant = "conv_${UUID.randomUUID().toString().take(8)}"
        com.menuflow.tenant.TenantContext.set(tenant)
        return tenant
    }

    /** Liga/desliga o rastreamento e define quais plataformas estao configuradas. */
    private fun setupConfig(enabled: Boolean, meta: Boolean = false, google: Boolean = false) {
        com.menuflow.tenant.TenantContext.set(tenant)
        val config = tenantConfigRepository.findFirstByOrderByCreatedAtAsc() ?: TenantConfig()
        config.conversionTrackingEnabled = enabled
        config.metaPixelId = if (meta) "1234567890" else null
        config.metaAccessToken = if (meta) "EAAtoken-secreto" else null
        config.googleSgtmUrl = if (google) "https://sgtm.exemplo.com" else null
        config.googleMeasurementId = if (google) "G-ABC12345" else null
        tenantConfigRepository.save(config)
    }

    /** Persiste um pedido minimo real (a FK conversion_dispatches.order_id exige orders.id). */
    private fun newOrder(phone: String? = "11999990000", totalCents: Long = 5_000): Order {
        com.menuflow.tenant.TenantContext.set(tenant)
        return orderRepository.save(
            Order(
                orderNumber = "MF-C-${UUID.randomUUID().toString().take(8)}",
                customerPhone = phone,
                totalCents = totalCents,
            ),
        )
    }

    private fun dispatches(): List<ConversionDispatch> {
        com.menuflow.tenant.TenantContext.set(tenant)
        return conversionDispatchRepository.findByStatusIn(ConversionStatus.values().toList())
    }

    private fun event(orderId: UUID) = OrderPaidEvent(tenant, orderId, null, "11999990000", 5_000)

    @Test
    fun `rastreamento desligado nao cria despacho`() {
        bind()
        setupConfig(enabled = false, meta = true, google = true)
        val order = newOrder()

        conversionService.scheduleConversions(event(order.id!!))

        assertTrue(dispatches().isEmpty())
    }

    @Test
    fun `Meta configurada cria despacho META PENDING`() {
        bind()
        setupConfig(enabled = true, meta = true)
        val order = newOrder()

        conversionService.scheduleConversions(event(order.id!!))

        val list = dispatches()
        assertEquals(1, list.size)
        assertEquals(ConversionPlatform.META, list[0].platform)
        assertEquals(ConversionStatus.PENDING, list[0].status)
        assertEquals("order-${order.id}", list[0].eventId)
    }

    @Test
    fun `Google configurado cria despacho GOOGLE PENDING`() {
        bind()
        setupConfig(enabled = true, google = true)
        val order = newOrder()

        conversionService.scheduleConversions(event(order.id!!))

        val list = dispatches()
        assertEquals(1, list.size)
        assertEquals(ConversionPlatform.GOOGLE, list[0].platform)
        assertEquals(ConversionStatus.PENDING, list[0].status)
    }

    @Test
    fun `Meta e Google juntos criam dois despachos`() {
        bind()
        setupConfig(enabled = true, meta = true, google = true)
        val order = newOrder()

        conversionService.scheduleConversions(event(order.id!!))

        val platforms = dispatches().map { it.platform }.toSet()
        assertEquals(setOf(ConversionPlatform.META, ConversionPlatform.GOOGLE), platforms)
    }

    @Test
    fun `agendamento e idempotente por pedido e plataforma`() {
        bind()
        setupConfig(enabled = true, meta = true, google = true)
        val order = newOrder()

        conversionService.scheduleConversions(event(order.id!!))
        conversionService.scheduleConversions(event(order.id!!)) // reenvio

        // Continua com exatamente 2 despachos (META + GOOGLE), sem duplicar.
        assertEquals(2, dispatches().size)
    }

    @Test
    fun `hashPhone normaliza E164 sem mais e bate com SHA-256 de 55`() {
        bind()
        val esperado = sha256Hex("5511999999999")
        assertEquals(esperado, conversionService.hashPhone("+5511999999999"))
    }

    @Test
    fun `sha256 produz hex de 64 caracteres`() {
        bind()
        val hash = conversionService.sha256("qualquer-valor")
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in "0123456789abcdef" })
    }

    @Test
    fun `processDispatches marca SKIPPED quando atingiu o limite de tentativas`() {
        bind()
        setupConfig(enabled = true, meta = true)
        val order = newOrder()
        com.menuflow.tenant.TenantContext.set(tenant)
        val saved = conversionDispatchRepository.save(
            ConversionDispatch(
                orderId = order.id!!,
                platform = ConversionPlatform.META,
                status = ConversionStatus.FAILED,
                attempts = 3,
                eventId = "order-${order.id}",
            ),
        )

        conversionService.processDispatches(tenant)

        com.menuflow.tenant.TenantContext.set(tenant)
        val after = conversionDispatchRepository.findById(saved.id!!).orElseThrow()
        assertEquals(ConversionStatus.SKIPPED, after.status)
        assertEquals(3, after.attempts) // nao tentou de novo
    }

    /** SHA-256 hex de referencia (independente da implementacao do servico). */
    private fun sha256Hex(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
