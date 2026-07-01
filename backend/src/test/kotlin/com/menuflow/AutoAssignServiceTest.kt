package com.menuflow

import com.menuflow.dto.OrderCreateRequest
import com.menuflow.dto.OrderItemRequest
import com.menuflow.dto.ProductCreateRequest
import com.menuflow.model.DeliveryDriver
import com.menuflow.model.DeliveryOffer
import com.menuflow.model.DeliveryOfferStatus
import com.menuflow.model.OrderType
import com.menuflow.repository.tenant.DeliveryDriverRepository
import com.menuflow.repository.tenant.DeliveryOfferRepository
import com.menuflow.repository.tenant.OrderRepository
import com.menuflow.repository.tenant.TenantConfigRepository
import com.menuflow.security.AuthPrincipal
import com.menuflow.service.AutoAssignService
import com.menuflow.service.DeliveryService
import com.menuflow.service.OrderService
import com.menuflow.service.ProductService
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.UUID

/**
 * Auto-assign de entrega (Fase 6.1). Semeia config, entregadores em turno com
 * localizacao e um pedido de entrega com geocode, e verifica que a oferta e criada
 * para o motoboy mais proximo dentro do raio. Cobre tambem o IDOR de aceite de oferta.
 */
class AutoAssignServiceTest @Autowired constructor(
    private val autoAssignService: AutoAssignService,
    private val deliveryService: DeliveryService,
    private val orderService: OrderService,
    private val productService: ProductService,
    private val driverRepository: DeliveryDriverRepository,
    private val offerRepository: DeliveryOfferRepository,
    private val orderRepository: OrderRepository,
    private val tenantConfigRepository: TenantConfigRepository,
    private val tenantTx: TenantTestTx,
) : IntegrationTestBase() {

    private lateinit var tenant: String
    private val tenantUuid = UUID.randomUUID()

    // Coordenadas do pedido (centro de Sao Paulo) e dos motoboys.
    private val orderLat = -23.550_000
    private val orderLng = -46.633_000

    @BeforeEach
    fun bind() {
        tenant = "auto_" + UUID.randomUUID().toString().take(8)
        setPrincipal(UUID.randomUUID(), "ADMIN")
    }

    @AfterEach
    fun clear() {
        TenantContext.clear()
        SecurityContextHolder.clearContext()
    }

    private fun setPrincipal(userId: UUID, role: String) {
        TenantContext.set(tenant)
        val principal = AuthPrincipal(userId, tenant, tenantUuid, listOf(role))
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, listOf(SimpleGrantedAuthority("ROLE_" + role)))
    }

    private fun enableConfig(radiusKm: Double = 10.0, autoAssign: Boolean = true) {
        TenantContext.set(tenant)
        tenantTx.run {
            val config = tenantConfigRepository.findFirstByOrderByCreatedAtAsc()!!
            config.autoAssignEnabled = autoAssign
            config.maxOfferRadiusKm = radiusKm
            config.deliveryBaseFeeCents = 500
            config.deliveryFeePerKmCents = 200
            config.offerTimeoutSeconds = 45
            tenantConfigRepository.save(config)
        }
    }

    private fun seedDriver(userId: UUID?, lat: Double?, lng: Double?, shift: Boolean = true): UUID {
        TenantContext.set(tenant)
        return tenantTx.run {
            driverRepository.save(
                DeliveryDriver(
                    name = "Moto " + UUID.randomUUID().toString().take(4),
                    phone = "5599" + (1000000..9999999).random().toString(),
                    tenantId = tenantUuid,
                    userId = userId,
                    active = true,
                    activeShift = shift,
                    lastLat = lat,
                    lastLng = lng,
                    lastLocationAt = if (lat != null) Instant.now() else null,
                ),
            ).id!!
        }
    }

    /** Cria um pedido DELIVERY e carimba o geocode; devolve o pedido (destacado). */
    private fun newDeliveryOrderWithGeo(lat: Double, lng: Double): com.menuflow.model.Order {
        TenantContext.set(tenant)
        val product = productService.create(
            ProductCreateRequest(
                categoryId = UUID.randomUUID(),
                sku = "AA-" + UUID.randomUUID().toString().take(6),
                name = "Burger",
                priceCents = 2000,
            ),
        )
        val orderId = orderService.create(
            OrderCreateRequest(
                orderType = OrderType.DELIVERY,
                items = listOf(OrderItemRequest(productId = product.id, quantity = 1)),
                deliveryFeeCents = 500,
            ),
            userId = null,
        ).id
        TenantContext.set(tenant)
        return tenantTx.run {
            val o = orderRepository.findById(orderId).get()
            o.deliveryLat = lat
            o.deliveryLng = lng
            orderRepository.save(o)
        }
    }

    @Test
    fun `auto-assign cria oferta para o motoboy mais proximo`() {
        enableConfig(radiusKm = 20.0)
        val near = seedDriver(UUID.randomUUID(), orderLat + 0.001, orderLng + 0.001) // ~0.15 km
        seedDriver(UUID.randomUUID(), orderLat - 0.1, orderLng - 0.1)                // ~15 km
        val order = newDeliveryOrderWithGeo(orderLat, orderLng)

        val offer = autoAssignService.tryAutoAssign(order, tenant)

        assertTrue(offer != null, "deveria criar uma oferta")
        assertEquals(near, offer!!.driverId)
        assertEquals(DeliveryOfferStatus.OFFERED, offer.status)
        assertTrue(offer.feeCents >= 500, "tarifa inclui a base")

        TenantContext.set(tenant)
        val persisted = tenantTx.run { offerRepository.findByOrderIdAndStatus(order.id!!, DeliveryOfferStatus.OFFERED) }
        assertEquals(1, persisted.size)
    }

    @Test
    fun `auto-assign desligado nao cria oferta`() {
        enableConfig(autoAssign = false)
        seedDriver(UUID.randomUUID(), orderLat, orderLng)
        val order = newDeliveryOrderWithGeo(orderLat, orderLng)

        assertNull(autoAssignService.tryAutoAssign(order, tenant))
    }

    @Test
    fun `motoboy fora do raio nao recebe oferta`() {
        enableConfig(radiusKm = 1.0)
        seedDriver(UUID.randomUUID(), orderLat - 0.1, orderLng - 0.1) // ~15 km, fora de 1 km
        val order = newDeliveryOrderWithGeo(orderLat, orderLng)

        assertNull(autoAssignService.tryAutoAssign(order, tenant))
    }

    @Test
    fun `expireStaleOffers marca ofertas vencidas como EXPIRED`() {
        val driverId = seedDriver(UUID.randomUUID(), orderLat, orderLng)
        val order = newDeliveryOrderWithGeo(orderLat, orderLng)
        TenantContext.set(tenant)
        val offerId = tenantTx.run {
            offerRepository.save(
                DeliveryOffer(
                    orderId = order.id!!,
                    driverId = driverId,
                    feeCents = 700,
                    distanceKm = 1.0,
                    expiresAt = Instant.now().minusSeconds(60), // ja vencida
                ),
            ).id!!
        }

        TenantContext.set(tenant)
        val expired = autoAssignService.expireStaleOffers()
        assertEquals(1, expired)

        TenantContext.set(tenant)
        val reloaded = tenantTx.run { offerRepository.findById(offerId).get() }
        assertEquals(DeliveryOfferStatus.EXPIRED, reloaded.status)
    }

    @Test
    fun `motoboy nao pode aceitar oferta de outro (IDOR)`() {
        enableConfig()
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        val driverA = seedDriver(userA, orderLat, orderLng)
        seedDriver(userB, orderLat, orderLng)
        val order = newDeliveryOrderWithGeo(orderLat, orderLng)
        TenantContext.set(tenant)
        val offerId = tenantTx.run {
            offerRepository.save(
                DeliveryOffer(
                    orderId = order.id!!,
                    driverId = driverA,
                    feeCents = 700,
                    expiresAt = Instant.now().plusSeconds(60),
                ),
            ).id!!
        }

        // Motoboy B tenta aceitar a oferta de A -> negado.
        setPrincipal(userB, "DRIVER")
        assertThrows(AccessDeniedException::class.java) { deliveryService.acceptOffer(offerId) }

        // Motoboy A (dono) aceita -> pedido atribuido a A.
        setPrincipal(userA, "DRIVER")
        val accepted = deliveryService.acceptOffer(offerId)
        assertEquals(DeliveryOfferStatus.ACCEPTED, accepted.status)

        TenantContext.set(tenant)
        val reloadedOrder = tenantTx.run { orderRepository.findById(order.id!!).get() }
        assertEquals(driverA, reloadedOrder.driverId)
    }
}
