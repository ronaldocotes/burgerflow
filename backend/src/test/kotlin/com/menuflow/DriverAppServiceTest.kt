package com.menuflow

import com.menuflow.dto.AssignDriverRequest
import com.menuflow.dto.DeliveryStatusUpdateRequest
import com.menuflow.dto.LocationUpdateRequest
import com.menuflow.dto.OrderCreateRequest
import com.menuflow.dto.OrderItemRequest
import com.menuflow.dto.OrderStatusUpdateRequest
import com.menuflow.dto.ProductCreateRequest
import com.menuflow.exception.BusinessException
import com.menuflow.exception.ConflictException
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.model.DeliveryDriver
import com.menuflow.model.DeliveryOffer
import com.menuflow.model.DeliveryOfferStatus
import com.menuflow.model.DeliveryStatus
import com.menuflow.model.DriverConfig
import com.menuflow.model.Order
import com.menuflow.model.OrderStatus
import com.menuflow.model.OrderType
import com.menuflow.model.control.Tenant
import com.menuflow.model.control.User
import com.menuflow.model.control.UserRole
import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.control.UserRepository
import com.menuflow.repository.tenant.DeliveryDriverRepository
import com.menuflow.repository.tenant.DeliveryOfferRepository
import com.menuflow.repository.tenant.DriverConfigRepository
import com.menuflow.repository.tenant.OrderRepository
import com.menuflow.security.AuthPrincipal
import com.menuflow.service.DeliveryService
import com.menuflow.service.OrderService
import com.menuflow.service.ProductService
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Fase 6.2 — API do app do motoboy (nivel de servico, Postgres real via
 * Testcontainers). Prova, com casos adversariais:
 *  - /me, /shift (proprio), /offers/my e /earnings/my resolvem o motoboy SEMPRE
 *    pelo user do token (elo user_id); user sem vinculo -> 403;
 *  - A1 (anti-BOLA): driver B nao avanca a entrega do driver A -> 403; o proprio
 *    driver avanca; repetir o mesmo status alvo e no-op idempotente (retry);
 *  - DELIVERED do despacho promove pedido READY -> DELIVERED (completedAt) e
 *    carimba firstDeliveryAt do entregador;
 *  - ganhos batem com a MESMA contagem do acerto financeiro (status DELIVERED +
 *    completedAt no periodo) x config em centavos; sem config -> zeros;
 *  - B1 (LGPD): GPS fora de turno -> 409;
 *  - vinculo user<->driver: papel errado 400, outro tenant 404 (sem vazar),
 *    mesmo user em 2 drivers 409 (indice unico V35), desvincular libera.
 */
class DriverAppServiceTest @Autowired constructor(
    private val deliveryService: DeliveryService,
    private val orderService: OrderService,
    private val productService: ProductService,
    private val driverRepository: DeliveryDriverRepository,
    private val driverConfigRepository: DriverConfigRepository,
    private val offerRepository: DeliveryOfferRepository,
    private val orderRepository: OrderRepository,
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
) : IntegrationTestBase() {

    private lateinit var tenant: String
    private val tenantUuid = UUID.randomUUID()
    private val zone = ZoneId.of("America/Sao_Paulo")

    @BeforeEach
    fun bind() {
        tenant = "drvapp_${UUID.randomUUID().toString().take(8)}"
        TenantContext.set(tenant)
    }

    @AfterEach
    fun clear() {
        TenantContext.clear()
        SecurityContextHolder.clearContext()
    }

    private fun bindPrincipal(userId: UUID, vararg roles: String, tUuid: UUID = tenantUuid) {
        val principal = AuthPrincipal(
            userId = userId,
            tenantSlug = tenant,
            tenantUuid = tUuid,
            roles = roles.toList(),
        )
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            principal,
            null,
            roles.map { SimpleGrantedAuthority("ROLE_$it") },
        )
    }

    private fun newDriver(userId: UUID? = null, shift: Boolean = true): DeliveryDriver {
        TenantContext.set(tenant)
        return driverRepository.save(
            DeliveryDriver(
                name = "Moto ${UUID.randomUUID().toString().take(6)}",
                // Telefone unico (indice UNICO parcial em phone desde a V41).
                phone = "55" + UUID.randomUUID().toString().filter { it.isDigit() }.take(11).padEnd(11, '9'),
                tenantId = tenantUuid,
                userId = userId,
                activeShift = shift,
            ),
        )
    }

    private fun newDeliveryOrderId(): UUID {
        TenantContext.set(tenant)
        val product = productService.create(
            ProductCreateRequest(
                categoryId = UUID.randomUUID(),
                sku = "DA-${UUID.randomUUID().toString().take(6)}",
                name = "Burger",
                priceCents = 2000,
            ),
        )
        return orderService.create(
            OrderCreateRequest(
                orderType = OrderType.DELIVERY,
                items = listOf(OrderItemRequest(productId = product.id, quantity = 1)),
                deliveryFeeCents = 500,
            ),
            userId = null,
        ).id
    }

    /** Pedido DELIVERED carimbado com o entregador (mesmo seed do DriverSettlementTest). */
    private fun seedDelivered(driverId: UUID?, status: OrderStatus, completedAt: Instant?) {
        TenantContext.set(tenant)
        orderRepository.save(
            Order(
                orderNumber = "DA-${UUID.randomUUID().toString().take(10)}",
                status = status,
                subtotalCents = 1000,
                totalCents = 1000,
                driverId = driverId,
                completedAt = completedAt,
            ),
        )
    }

    // ------------------------------------------------------------------ /me

    @Test
    fun `me - resolves driver by token user and exposes pay config`() {
        val userA = UUID.randomUUID()
        val driver = newDriver(userId = userA)
        driverConfigRepository.save(
            DriverConfig(driverId = driver.id!!, dailyRateCents = 5_000, perDeliveryCents = 300, perKmCents = 50),
        )

        bindPrincipal(userA, "DRIVER")
        val me = deliveryService.me()
        assertEquals(driver.id, me.id)
        assertTrue(me.activeShift)
        assertNotNull(me.payConfig)
        assertEquals(300, me.payConfig!!.perDeliveryCents)
        assertEquals(5_000, me.payConfig!!.dailyRateCents)

        // Adversarial: user autenticado mas sem vinculo com entregador -> 403.
        bindPrincipal(UUID.randomUUID(), "DRIVER")
        assertThrows(AccessDeniedException::class.java) { deliveryService.me() }
    }

    @Test
    fun `me - without pay config returns null payConfig`() {
        val userA = UUID.randomUUID()
        newDriver(userId = userA)
        bindPrincipal(userA, "DRIVER")
        assertNull(deliveryService.me().payConfig)
    }

    // ------------------------------------------------------------- /shift (own)

    @Test
    fun `setOwnShift - toggles only the callers driver`() {
        val userA = UUID.randomUUID()
        val a = newDriver(userId = userA, shift = false)
        val b = newDriver(userId = UUID.randomUUID(), shift = false)

        bindPrincipal(userA, "DRIVER")
        val resp = deliveryService.setOwnShift(true)
        assertEquals(a.id, resp.id)
        assertTrue(resp.activeShift)

        TenantContext.set(tenant)
        assertFalse(driverRepository.findById(b.id!!).get().activeShift)
    }

    // ------------------------------------------------------------- /offers/my

    @Test
    fun `myOffers - only own pending non-expired offers`() {
        val userA = UUID.randomUUID()
        val a = newDriver(userId = userA)
        val b = newDriver(userId = UUID.randomUUID())

        bindPrincipal(UUID.randomUUID(), "ADMIN")
        // uq_active_offer_per_order (V40): UMA oferta OFFERED por pedido -> cada
        // oferta OFFERED do cenario usa o proprio pedido.
        val order1 = newDeliveryOrderId()
        val order2 = newDeliveryOrderId()
        val order3 = newDeliveryOrderId()
        val now = Instant.now()

        TenantContext.set(tenant)
        val valid = offerRepository.save(
            DeliveryOffer(orderId = order1, driverId = a.id, feeCents = 700, expiresAt = now.plusSeconds(120)),
        )
        // Ruido: expirada (OFFERED com prazo vencido), ja aceita, e de outro entregador.
        offerRepository.save(
            DeliveryOffer(orderId = order2, driverId = a.id, feeCents = 700, expiresAt = now.minusSeconds(60)),
        )
        offerRepository.save(
            DeliveryOffer(
                orderId = order1, driverId = a.id, feeCents = 700,
                status = DeliveryOfferStatus.ACCEPTED, expiresAt = now.plusSeconds(120),
            ),
        )
        offerRepository.save(
            DeliveryOffer(orderId = order3, driverId = b.id, feeCents = 700, expiresAt = now.plusSeconds(120)),
        )

        bindPrincipal(userA, "DRIVER")
        val offers = deliveryService.myOffers()
        assertEquals(1, offers.size)
        assertEquals(valid.id, offers.first().id)
    }

    // ----------------------------------------------- status do despacho (A1)

    @Test
    fun `updateStatus - driver B cannot touch driver A order, owner can, retry is idempotent`() {
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        val a = newDriver(userId = userA)
        newDriver(userId = userB)

        bindPrincipal(UUID.randomUUID(), "ADMIN")
        val orderId = newDeliveryOrderId()
        deliveryService.assign(orderId, AssignDriverRequest(driverId = a.id!!))

        // Adversarial (A1): driver B tenta fechar a entrega do driver A -> 403.
        bindPrincipal(userB, "DRIVER")
        assertThrows(AccessDeniedException::class.java) {
            deliveryService.updateStatus(orderId, DeliveryStatusUpdateRequest(DeliveryStatus.OUT_FOR_DELIVERY))
        }

        // O dono avanca normalmente.
        bindPrincipal(userA, "DRIVER")
        val out = deliveryService.updateStatus(orderId, DeliveryStatusUpdateRequest(DeliveryStatus.OUT_FOR_DELIVERY))
        assertEquals(DeliveryStatus.OUT_FOR_DELIVERY, out.deliveryStatus)

        // Retry do app com o MESMO alvo: no-op 200, nao 400 de transicao invalida.
        val retry = deliveryService.updateStatus(orderId, DeliveryStatusUpdateRequest(DeliveryStatus.OUT_FOR_DELIVERY))
        assertEquals(DeliveryStatus.OUT_FOR_DELIVERY, retry.deliveryStatus)

        // Driver sem vinculo nenhum tambem e barrado.
        bindPrincipal(UUID.randomUUID(), "DRIVER")
        assertThrows(AccessDeniedException::class.java) {
            deliveryService.updateStatus(orderId, DeliveryStatusUpdateRequest(DeliveryStatus.DELIVERED))
        }
    }

    @Test
    fun `updateStatus DELIVERED - promotes READY kitchen order and stamps firstDeliveryAt`() {
        val userA = UUID.randomUUID()
        val a = newDriver(userId = userA)

        bindPrincipal(UUID.randomUUID(), "ADMIN")
        val orderId = newDeliveryOrderId()
        // Cozinha: PENDING -> PREPARING -> READY (FSM do OrderService).
        orderService.updateStatus(orderId, OrderStatusUpdateRequest(status = OrderStatus.PREPARING))
        orderService.updateStatus(orderId, OrderStatusUpdateRequest(status = OrderStatus.READY))
        deliveryService.assign(orderId, AssignDriverRequest(driverId = a.id!!))

        bindPrincipal(userA, "DRIVER")
        deliveryService.updateStatus(orderId, DeliveryStatusUpdateRequest(DeliveryStatus.OUT_FOR_DELIVERY))
        deliveryService.updateStatus(orderId, DeliveryStatusUpdateRequest(DeliveryStatus.DELIVERED))

        TenantContext.set(tenant)
        val order = orderRepository.findById(orderId).get()
        assertEquals(OrderStatus.DELIVERED, order.status)
        assertNotNull(order.completedAt)
        assertEquals(DeliveryStatus.DELIVERED, order.deliveryStatus)
        assertNotNull(driverRepository.findById(a.id!!).get().firstDeliveryAt)
    }

    // ------------------------------------------------------------ /earnings/my

    @Test
    fun `myEarnings - counts settlement deliveries times config cents`() {
        val userA = UUID.randomUUID()
        val a = newDriver(userId = userA)
        val other = newDriver(userId = UUID.randomUUID())
        TenantContext.set(tenant)
        driverConfigRepository.save(
            DriverConfig(driverId = a.id!!, dailyRateCents = 5_000, perDeliveryCents = 350, perKmCents = 0),
        )

        val now = Instant.now()
        repeat(3) { seedDelivered(a.id, OrderStatus.DELIVERED, now) }
        // Ruido: outro entregador, nao-DELIVERED, fora do periodo.
        seedDelivered(other.id, OrderStatus.DELIVERED, now)
        seedDelivered(a.id, OrderStatus.PREPARING, now)
        seedDelivered(a.id, OrderStatus.DELIVERED, now.minus(5, ChronoUnit.DAYS))

        bindPrincipal(userA, "DRIVER")
        val today = LocalDate.now(zone)
        val earnings = deliveryService.myEarnings(today, today)
        assertEquals(3, earnings.deliveriesCount)
        assertEquals(3 * 350L, earnings.deliveryEarningsCents)
        assertEquals(350, earnings.perDeliveryCents)
        assertTrue(earnings.hasConfig)

        // Sem parametros: default = hoje (mesmo resultado).
        val defaulted = deliveryService.myEarnings(null, null)
        assertEquals(3, defaulted.deliveriesCount)
    }

    @Test
    fun `myEarnings - without config returns zeros and hasConfig false, bad range 400`() {
        val userA = UUID.randomUUID()
        val a = newDriver(userId = userA)
        seedDelivered(a.id, OrderStatus.DELIVERED, Instant.now())

        bindPrincipal(userA, "DRIVER")
        val today = LocalDate.now(zone)
        val earnings = deliveryService.myEarnings(today, today)
        assertEquals(1, earnings.deliveriesCount)
        assertEquals(0, earnings.deliveryEarningsCents)
        assertFalse(earnings.hasConfig)

        assertThrows(BusinessException::class.java) {
            deliveryService.myEarnings(today, today.minusDays(1))
        }
    }

    // ------------------------------------------------------- GPS fora de turno (B1)

    @Test
    fun `updateLocation - rejected while off shift, accepted on shift`() {
        val userA = UUID.randomUUID()
        newDriver(userId = userA, shift = false)

        bindPrincipal(userA, "DRIVER")
        assertThrows(ConflictException::class.java) {
            deliveryService.updateLocation(LocationUpdateRequest(lat = 0.03, lng = -51.06, batteryPct = 88))
        }

        deliveryService.setOwnShift(true)
        val resp = deliveryService.updateLocation(LocationUpdateRequest(lat = 0.03, lng = -51.06, batteryPct = 88))
        assertEquals(0.03, resp.lastLat)
        assertNotNull(resp.lastLocationAt)
    }

    // ------------------------------------------------- vinculo user <-> driver

    @Test
    fun `linkDriverUser - validates role and tenant, enforces uniqueness, unlink frees`() {
        // Tenants/users reais no banco de CONTROLE.
        val ctrlTenant = tenantRepository.save(Tenant(slug = tenant, displayName = "Drv App"))
        val otherTenant = tenantRepository.save(
            Tenant(slug = "other_${UUID.randomUUID().toString().take(8)}", displayName = "Outro"),
        )
        val driverUser = userRepository.save(
            User(
                tenantId = ctrlTenant.id!!,
                email = "moto@$tenant.com",
                passwordHash = passwordEncoder.encode("pass1234"),
                firstName = "Moto",
                role = UserRole.DRIVER,
            ),
        )
        val staffUser = userRepository.save(
            User(
                tenantId = ctrlTenant.id!!,
                email = "staff@$tenant.com",
                passwordHash = passwordEncoder.encode("pass1234"),
                firstName = "Staff",
                role = UserRole.ADMIN,
            ),
        )
        val foreignUser = userRepository.save(
            User(
                tenantId = otherTenant.id!!,
                email = "moto@other.com",
                passwordHash = passwordEncoder.encode("pass1234"),
                firstName = "Alheio",
                role = UserRole.DRIVER,
            ),
        )

        bindPrincipal(staffUser.id!!, "ADMIN", tUuid = ctrlTenant.id!!)
        val d1 = newDriver()
        val d2 = newDriver()

        // Vincula: elo persiste.
        deliveryService.linkDriverUser(d1.id!!, driverUser.id)
        TenantContext.set(tenant)
        assertEquals(driverUser.id, driverRepository.findById(d1.id!!).get().userId)

        // Papel errado -> 400.
        assertThrows(BusinessException::class.java) {
            deliveryService.linkDriverUser(d2.id!!, staffUser.id)
        }
        // Usuario de OUTRO tenant -> 404 generico (nao vaza existencia).
        assertThrows(ResourceNotFoundException::class.java) {
            deliveryService.linkDriverUser(d2.id!!, foreignUser.id)
        }
        // Mesmo user em dois entregadores -> 409 (indice unico parcial da V35).
        assertThrows(ConflictException::class.java) {
            deliveryService.linkDriverUser(d2.id!!, driverUser.id)
        }

        // Desvincular libera o user para outro entregador.
        deliveryService.linkDriverUser(d1.id!!, null)
        TenantContext.set(tenant)
        assertNull(driverRepository.findById(d1.id!!).get().userId)
        deliveryService.linkDriverUser(d2.id!!, driverUser.id)
        TenantContext.set(tenant)
        assertEquals(driverUser.id, driverRepository.findById(d2.id!!).get().userId)
    }
}
