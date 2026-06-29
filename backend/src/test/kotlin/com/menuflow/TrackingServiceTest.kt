package com.menuflow

import com.menuflow.dto.OrderCreateRequest
import com.menuflow.dto.OrderItemRequest
import com.menuflow.dto.ProductCreateRequest
import com.menuflow.dto.TrackingLinkCreateRequest
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.model.MarketingEventType
import com.menuflow.model.PaymentMethod
import com.menuflow.repository.tenant.MarketingEventRepository
import com.menuflow.service.OrderService
import com.menuflow.service.ProductService
import com.menuflow.service.TrackingService
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Tracking first-party (Fase 3.6) contra um Postgres real (Testcontainers). Prova o
 * clique (registro + contador atomico + anonimizacao de IP), a conversao idempotente
 * via fluxo real do pedido, o resumo ROAS e a geracao de slug.
 *
 * Cada caso usa seu PROPRIO tenant (db isolado). Nao e @Transactional: cada save
 * commita, entao as contagens enxergam o que foi semeado antes.
 */
class TrackingServiceTest @Autowired constructor(
    private val trackingService: TrackingService,
    private val orderService: OrderService,
    private val productService: ProductService,
    private val marketingEventRepository: MarketingEventRepository,
) : IntegrationTestBase() {

    private lateinit var tenant: String

    @AfterEach
    fun clear() = TenantContext.clear()

    private fun bind(): String {
        tenant = "track_${UUID.randomUUID().toString().take(8)}"
        TenantContext.set(tenant)
        return tenant
    }

    private fun newLink(name: String = "Campanha WhatsApp", source: String = "whatsapp-junho"): UUID {
        TenantContext.set(tenant)
        return trackingService.create(
            TrackingLinkCreateRequest(name = name, source = source, medium = "messaging", campaign = "junho"),
        ).id
    }

    /** Produto simples (sem ficha tecnica -> sem baixa de estoque) para gerar pedidos. */
    private fun newProduct(price: Long = 3_000): UUID {
        TenantContext.set(tenant)
        return productService.create(
            ProductCreateRequest(
                categoryId = UUID.randomUUID(),
                sku = "TRK-${UUID.randomUUID().toString().take(6)}",
                name = "Burger",
                priceCents = price,
            ),
        ).id
    }

    private fun slugOf(linkId: UUID): String {
        TenantContext.set(tenant)
        return trackingService.list(org.springframework.data.domain.PageRequest.of(0, 50))
            .content.first { it.id == linkId }.slug
    }

    @Test
    fun `create gera slug e destinationUrl com UTM`() {
        bind()
        val link = trackingService.create(
            TrackingLinkCreateRequest(name = "Insta Bio", source = "instagram-bio", medium = "social"),
        )
        assertEquals(8, link.slug.length, "slug tem 8 chars")
        assertTrue(link.slug.all { it.isLowerCase() || it.isDigit() }, "slug alfanumerico minusculo")
        assertTrue(link.destinationUrl.contains("utm_source=instagram-bio"))
        assertTrue(link.destinationUrl.contains("src=${link.slug}"))
        assertTrue(link.shareUrl.endsWith("/${link.slug}"))
        assertEquals(0L, link.clickCount)
    }

    @Test
    fun `recordClick registra evento CLICK e incrementa clickCount`() {
        bind()
        val linkId = newLink()
        val slug = slugOf(linkId)

        TenantContext.set(tenant)
        val redirect = trackingService.recordClick(slug, ip = "200.1.2.3", userAgent = "Mozilla/5.0")
        assertEquals("whatsapp-junho", redirect.source)
        assertTrue(redirect.destinationUrl.contains("utm_source=whatsapp-junho"))

        TenantContext.set(tenant)
        // Um clique a mais => clickCount = 1.
        val clickCount = trackingService.list(org.springframework.data.domain.PageRequest.of(0, 10))
            .content.first { it.id == linkId }.clickCount
        assertEquals(1L, clickCount)

        TenantContext.set(tenant)
        val events = marketingEventRepository.findAll().filter { it.eventType == MarketingEventType.CLICK }
        assertEquals(1, events.size)
        assertEquals("200.1.2.0", events.first().customerIp, "IPv4 anonimizado (ultimo octeto zerado)")
    }

    @Test
    fun `recordClick com slug inexistente lança 404`() {
        bind()
        TenantContext.set(tenant)
        assertThrows(ResourceNotFoundException::class.java) {
            trackingService.recordClick("naoexiste", ip = null, userAgent = null)
        }
    }

    @Test
    fun `recordClick em link inativo lança 404`() {
        bind()
        val linkId = newLink()
        val slug = slugOf(linkId)
        TenantContext.set(tenant)
        trackingService.deactivate(linkId)
        TenantContext.set(tenant)
        assertThrows(ResourceNotFoundException::class.java) {
            trackingService.recordClick(slug, ip = "10.0.0.1", userAgent = null)
        }
    }

    @Test
    fun `anonimizacao de IP cobre IPv4 e IPv6`() {
        assertEquals("192.168.1.0", TrackingService.anonymizeIp("192.168.1.100"))
        assertNull(TrackingService.anonymizeIp(null))
        assertNull(TrackingService.anonymizeIp(""))
        val v6 = TrackingService.anonymizeIp("2001:0db8:85a3:1234:5678:8a2e:0370:7334")
        assertTrue(v6!!.startsWith("2001:0db8:85a3:1234"), "mantem os primeiros 64 bits")
        assertTrue(v6.endsWith("::"), "zera o resto")
        assertFalse(v6.contains("8a2e"), "descarta os bits de host")
        assertFalse(v6.contains("7334"))
    }

    @Test
    fun `recordConversion registra CONVERSION com revenueCents e é idempotente`() {
        bind()
        val linkId = newLink()
        val orderId = UUID.randomUUID().let { _ ->
            // Cria um pedido real (FK marketing_events.order_id -> orders(id)).
            TenantContext.set(tenant)
            val product = newProduct(price = 3_000)
            orderService.create(
                OrderCreateRequest(
                    items = listOf(OrderItemRequest(productId = product, quantity = 2)),
                    paymentMethod = PaymentMethod.PIX,
                ),
                userId = null,
            ).id
        }

        // 1a conversao.
        TenantContext.set(tenant)
        trackingService.recordConversion(orderId, linkId, revenueCents = 6_000)
        // 2a conversao do MESMO pedido: idempotente (nao duplica).
        TenantContext.set(tenant)
        trackingService.recordConversion(orderId, linkId, revenueCents = 6_000)

        TenantContext.set(tenant)
        val conversions = marketingEventRepository.findAll()
            .filter { it.eventType == MarketingEventType.CONVERSION && it.orderId == orderId }
        assertEquals(1, conversions.size, "1 CONVERSION por pedido (idempotente)")
        assertEquals(6_000L, conversions.first().revenueCents)
    }

    @Test
    fun `getSummary agrega clicks, conversions e revenue por link`() {
        bind()
        val linkId = newLink()
        val slug = slugOf(linkId)

        // 3 cliques.
        repeat(3) {
            TenantContext.set(tenant)
            trackingService.recordClick(slug, ip = "8.8.8.8", userAgent = "ua")
        }
        // 1 conversao de R$ 60,00 via pedido real.
        TenantContext.set(tenant)
        val product = newProduct(price = 3_000)
        TenantContext.set(tenant)
        val orderId = orderService.create(
            OrderCreateRequest(
                items = listOf(OrderItemRequest(productId = product, quantity = 2)),
                paymentMethod = PaymentMethod.PIX,
            ),
            userId = null,
        ).id
        TenantContext.set(tenant)
        trackingService.recordConversion(orderId, linkId, revenueCents = 6_000)

        TenantContext.set(tenant)
        val summary = trackingService.getSummary(
            from = Instant.now().minus(1, ChronoUnit.DAYS),
            to = Instant.now().plus(1, ChronoUnit.DAYS),
        )
        val row = summary.first { it.trackingLinkId == linkId }
        assertEquals(3L, row.clicks)
        assertEquals(1L, row.conversions)
        assertEquals(6_000L, row.revenueCents)
        assertEquals(1.0 / 3.0, row.conversionRate, 0.0001)
    }

    @Test
    fun `getSummary com link sem eventos retorna zeros`() {
        bind()
        val linkId = newLink()
        TenantContext.set(tenant)
        val summary = trackingService.getSummary(from = null, to = null)
        val row = summary.first { it.trackingLinkId == linkId }
        assertEquals(0L, row.clicks)
        assertEquals(0L, row.conversions)
        assertEquals(0L, row.revenueCents)
        assertEquals(0.0, row.conversionRate, "NaN-safe: 0 cliques -> taxa 0.0")
    }
}
