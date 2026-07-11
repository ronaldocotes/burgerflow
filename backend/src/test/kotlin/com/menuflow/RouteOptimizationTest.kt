package com.menuflow

import com.fasterxml.jackson.databind.ObjectMapper
import com.menuflow.dto.AssignDriverRequest
import com.menuflow.dto.DeliveryAddressRequest
import com.menuflow.dto.DriverCreateRequest
import com.menuflow.dto.OrderCreateRequest
import com.menuflow.dto.OrderItemRequest
import com.menuflow.dto.ProductCreateRequest
import com.menuflow.dto.RouteAssignRequest
import com.menuflow.dto.RouteOptimizeRequest
import com.menuflow.dto.TenantConfigUpdateRequest
import com.menuflow.exception.BusinessException
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.model.DeliveryStatus
import com.menuflow.model.OrderType
import com.menuflow.model.control.Tenant
import com.menuflow.model.control.User
import com.menuflow.model.control.UserRole
import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.control.UserRepository
import com.menuflow.repository.tenant.DeliveryDriverRepository
import com.menuflow.repository.tenant.OrderRepository
import com.menuflow.security.AuthPrincipal
import com.menuflow.service.DeliveryService
import com.menuflow.service.OrderService
import com.menuflow.service.ProductService
import com.menuflow.service.RouteOptimizationService
import com.menuflow.service.TenantConfigService
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Issue #4 — roteirizacao de multiplas entregas (F1 stateless + F2 persistir a
 * sequencia). OSRM NAO esta configurado no teste (osrm.base-url vazio), entao a
 * otimizacao cai no FALLBACK deterministico (Haversine crescente a partir do
 * restaurante) — a logica do /trip real e provada isolada em [OsrmTripProviderTest].
 * Cobre:
 *  1. F1 fallback: sem OSRM, optimized=false + ordem por distancia + totais > 0;
 *  2. multi-tenant: id inexistente/de outro restaurante -> 404 (nao roteiriza);
 *  3. bound: acima do maximo de paradas -> 400;
 *  4. F2: assignRoute grava delivery_sequence nos pedidos certos, e idempotente e a
 *     resposta que o app consome (DeliveryOrderResponse) traz a sequencia;
 *  5. F2: motoboy que nao e da FROTA -> 400;
 *  6. RBAC HTTP: STAFF nao acessa /delivery/route/optimize (403); ADMIN passa a authz.
 */
@AutoConfigureMockMvc
class RouteOptimizationTest @Autowired constructor(
    private val routeOptimizationService: RouteOptimizationService,
    private val deliveryService: DeliveryService,
    private val orderService: OrderService,
    private val productService: ProductService,
    private val tenantConfigService: TenantConfigService,
    private val orderRepository: OrderRepository,
    private val driverRepository: DeliveryDriverRepository,
    private val mockMvc: MockMvc,
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val objectMapper: ObjectMapper,
) : IntegrationTestBase() {

    private lateinit var tenant: String
    private val tenantUuid = UUID.randomUUID()

    @BeforeEach
    fun bind() {
        tenant = "route_${UUID.randomUUID().toString().take(8)}"
        TenantContext.set(tenant)
        val principal = AuthPrincipal(
            userId = UUID.randomUUID(),
            tenantSlug = tenant,
            tenantUuid = tenantUuid,
            roles = listOf("ADMIN"),
        )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, listOf(SimpleGrantedAuthority("ROLE_ADMIN")))
        // Restaurante na origem (0,0): ponto de partida da rota.
        tenantConfigService.update(
            TenantConfigUpdateRequest(autoAcceptOrders = false, restaurantLat = 0.0, restaurantLng = 0.0),
        )
    }

    @AfterEach
    fun clear() {
        TenantContext.clear()
        SecurityContextHolder.clearContext()
    }

    /** Cria um pedido DELIVERY com coordenadas de entrega (lat variando so em latitude). */
    private fun newDeliveryOrder(lat: Double): UUID {
        TenantContext.set(tenant)
        val product = productService.create(
            ProductCreateRequest(
                categoryId = UUID.randomUUID(),
                sku = "R-${UUID.randomUUID().toString().take(6)}",
                name = "Burger",
                priceCents = 2000,
            ),
        )
        return orderService.create(
            OrderCreateRequest(
                orderType = OrderType.DELIVERY,
                items = listOf(OrderItemRequest(productId = product.id, quantity = 1)),
                deliveryFeeCents = 500,
                delivery = DeliveryAddressRequest(lat = lat, lng = 0.0),
            ),
            userId = null,
        ).id
    }

    // --- 1. F1 fallback deterministico sem OSRM ---
    @Test
    fun `optimize without OSRM falls back to Haversine order and totals`() {
        // Criados fora de ordem: far(0.02), near(0.005), mid(0.01).
        val far = newDeliveryOrder(0.02)
        val near = newDeliveryOrder(0.005)
        val mid = newDeliveryOrder(0.01)

        TenantContext.set(tenant)
        val res = routeOptimizationService.optimize(RouteOptimizeRequest(listOf(far, near, mid)))

        assertFalse(res.optimized, "sem OSRM configurado deve ser fallback (optimized=false)")
        assertEquals(listOf(near, mid, far), res.stops.map { it.orderId }, "ordem por distancia crescente ao restaurante")
        assertEquals(listOf(1, 2, 3), res.stops.map { it.position })
        assertTrue(res.totalDistanceMeters > 0, "total de distancia deve ser positivo")
        // Fallback nao estima tempo.
        assertEquals(null, res.totalDurationSeconds)
    }

    // --- 2. multi-tenant / id inexistente ---
    @Test
    fun `optimize rejects an unknown or cross-tenant order id`() {
        val real = newDeliveryOrder(0.005)
        TenantContext.set(tenant)
        val ghost = UUID.randomUUID() // nao existe neste banco (nem de outro tenant)
        assertThrows(ResourceNotFoundException::class.java) {
            routeOptimizationService.optimize(RouteOptimizeRequest(listOf(real, ghost)))
        }
    }

    // --- 3. bound de paradas ---
    @Test
    fun `optimize rejects above the max stops bound`() {
        val ids = (1..RouteOptimizationService.MAX_STOPS + 1).map { UUID.randomUUID() }
        TenantContext.set(tenant)
        val ex = assertThrows(BusinessException::class.java) {
            routeOptimizationService.optimize(RouteOptimizeRequest(ids))
        }
        assertTrue(ex.message!!.contains("maximo"), "erro deve citar o teto: ${ex.message}")
    }

    // --- 4. F2 grava a sequencia, idempotente, e o app ve o campo ---
    @Test
    fun `assignRoute persists delivery_sequence, is idempotent and exposes it to the app`() {
        val driver = deliveryService.createDriver(DriverCreateRequest("Zé Moto", "5599999999", "abc1d23"))
        val o1 = newDeliveryOrder(0.005)
        val o2 = newDeliveryOrder(0.02)
        val o3 = newDeliveryOrder(0.01)

        // Confirma a rota na ordem o2 -> o1 -> o3 (ordem decidida pelo F1/gestor).
        val sequence = listOf(o2, o1, o3)
        TenantContext.set(tenant)
        val assigned = routeOptimizationService.assignRoute(RouteAssignRequest(driver.id, sequence))

        // A resposta (shape do app) traz a sequencia, na ordem enviada.
        assertEquals(sequence, assigned.map { it.orderId })
        assertEquals(listOf(1, 2, 3), assigned.map { it.deliverySequence })
        assertTrue(assigned.all { it.driverId == driver.id })
        assertTrue(assigned.all { it.deliveryStatus == DeliveryStatus.ASSIGNED })

        // Persistiu no pedido certo.
        TenantContext.set(tenant)
        assertEquals(1, orderRepository.findById(o2).get().deliverySequence)
        assertEquals(2, orderRepository.findById(o1).get().deliverySequence)
        assertEquals(3, orderRepository.findById(o3).get().deliverySequence)

        // Idempotente: reenviar a MESMA rota converge para o mesmo estado.
        TenantContext.set(tenant)
        val again = routeOptimizationService.assignRoute(RouteAssignRequest(driver.id, sequence))
        assertEquals(listOf(1, 2, 3), again.map { it.deliverySequence })
        TenantContext.set(tenant)
        assertEquals(2, orderRepository.findById(o1).get().deliverySequence)

        // A query que o app consome (GET /delivery/orders/my) devolve a sequencia.
        TenantContext.set(tenant)
        val forDriver = orderRepository.findActiveOrdersForDriver(driver.id)
            .map { com.menuflow.dto.DeliveryOrderResponse.from(it) }
        assertEquals(setOf(1, 2, 3), forDriver.mapNotNull { it.deliverySequence }.toSet())
    }

    // --- MEDIO-2(a): re-roteirizar parcial nao deixa sequencia orfa ---
    @Test
    fun `re-routing without an order clears its stale sequence and unassigns it`() {
        val driver = deliveryService.createDriver(DriverCreateRequest("Zé", "5599999990"))
        val o1 = newDeliveryOrder(0.005)
        val o2 = newDeliveryOrder(0.01)
        val o3 = newDeliveryOrder(0.02)

        TenantContext.set(tenant)
        routeOptimizationService.assignRoute(RouteAssignRequest(driver.id, listOf(o1, o2, o3)))

        // Re-rota SEM o1: [o3, o2].
        TenantContext.set(tenant)
        val re = routeOptimizationService.assignRoute(RouteAssignRequest(driver.id, listOf(o3, o2)))
        assertEquals(listOf(o3, o2), re.map { it.orderId })
        assertEquals(listOf(1, 2), re.map { it.deliverySequence })

        // o1 saiu da rota: sequencia limpa, desassociado, de volta ao pool (era ASSIGNED).
        TenantContext.set(tenant)
        val dropped = orderRepository.findById(o1).get()
        assertEquals(null, dropped.deliverySequence)
        assertEquals(null, dropped.driverId)
        assertEquals(null, dropped.deliveryStatus)

        // O app do motoboy nao ve "parada 1" duplicada: so o2,o3 com sequencias 1,2.
        TenantContext.set(tenant)
        val active = orderRepository.findActiveOrdersForDriver(driver.id)
        assertEquals(2, active.size)
        assertEquals(setOf(1, 2), active.mapNotNull { it.deliverySequence }.toSet())
    }

    // --- MEDIO-2(b): assign single limpa sequencia stale ---
    @Test
    fun `single assign clears a stale route sequence`() {
        val driverA = deliveryService.createDriver(DriverCreateRequest("Zé", "5599999991"))
        val o1 = newDeliveryOrder(0.005)
        val o2 = newDeliveryOrder(0.01)
        TenantContext.set(tenant)
        routeOptimizationService.assignRoute(RouteAssignRequest(driverA.id, listOf(o1, o2)))
        TenantContext.set(tenant)
        assertEquals(1, orderRepository.findById(o1).get().deliverySequence)

        // Re-atribui o1 sozinho (assign single legado) a outro motoboy.
        val driverB = deliveryService.createDriver(DriverCreateRequest("Ana", "5599999992"))
        TenantContext.set(tenant)
        val assigned = deliveryService.assign(o1, AssignDriverRequest(driverB.id))
        assertEquals(null, assigned.deliverySequence, "assign single deve limpar a sequencia stale")
        TenantContext.set(tenant)
        assertEquals(null, orderRepository.findById(o1).get().deliverySequence)
    }

    // --- BAIXO-1: re-despachar um FAILED o revive como ASSIGNED (nao vira fantasma) ---
    @Test
    fun `routing a failed delivery revives it as ASSIGNED and visible to the app`() {
        val driver = deliveryService.createDriver(DriverCreateRequest("Zé", "5599999993"))
        val o1 = newDeliveryOrder(0.005)
        // Marca FAILED direto no banco (simula tentativa de entrega que falhou).
        TenantContext.set(tenant)
        val ord = orderRepository.findById(o1).get()
        ord.deliveryStatus = DeliveryStatus.FAILED
        orderRepository.save(ord)

        TenantContext.set(tenant)
        val res = routeOptimizationService.assignRoute(RouteAssignRequest(driver.id, listOf(o1)))
        assertEquals(DeliveryStatus.ASSIGNED, res[0].deliveryStatus, "FAILED re-despachado vira ASSIGNED")

        // Agora aparece para o app (findActiveOrdersForDriver exclui FAILED).
        TenantContext.set(tenant)
        val active = orderRepository.findActiveOrdersForDriver(driver.id)
        assertEquals(1, active.size, "pedido revivido nao pode ser parada fantasma")
        assertEquals(1, active[0].deliverySequence)
    }

    // --- 5. F2 recusa motoboy que nao e da FROTA ---
    @Test
    fun `assignRoute rejects a non-fleet driver`() {
        val driver = deliveryService.createDriver(DriverCreateRequest("Free Lancer", "5591111111"))
        TenantContext.set(tenant)
        // Rebaixa para FREELANCER direto no banco do tenant.
        val entity = driverRepository.findById(driver.id).get()
        entity.driverType = "FREELANCER"
        driverRepository.save(entity)

        val order = newDeliveryOrder(0.005)
        TenantContext.set(tenant)
        val ex = assertThrows(BusinessException::class.java) {
            routeOptimizationService.assignRoute(RouteAssignRequest(driver.id, listOf(order)))
        }
        assertTrue(ex.message!!.contains("FROTA"), "erro deve citar FROTA: ${ex.message}")
    }

    // --- 6. RBAC HTTP: STAFF 403; ADMIN passa a autorizacao ---
    @Test
    fun `route optimize requires OPERATOR MANAGER or ADMIN`() {
        // Requests HTTP autenticam pelo JWT nos filtros; limpa o contexto semeado no
        // @BeforeEach (usado pelos testes de servico direto) para nao interferir.
        SecurityContextHolder.clearContext()
        TenantContext.clear()
        val slug = "routehttp_${UUID.randomUUID().toString().take(8)}"
        val t = tenantRepository.save(Tenant(slug = slug, displayName = "Route Http Burger"))
        userRepository.save(
            User(tenantId = t.id!!, email = "admin@$slug.com", passwordHash = passwordEncoder.encode("pass1234"), firstName = "Admin", role = UserRole.ADMIN),
        )
        userRepository.save(
            User(tenantId = t.id!!, email = "staff@$slug.com", passwordHash = passwordEncoder.encode("pass1234"), firstName = "Staff", role = UserRole.STAFF),
        )

        fun login(email: String): String {
            val body = objectMapper.writeValueAsString(mapOf("email" to email, "password" to "pass1234", "tenantSlug" to slug))
            val res = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk).andReturn()
            return objectMapper.readTree(res.response.contentAsString).get("token").asText()
        }

        val adminToken = login("admin@$slug.com")
        val staffToken = login("staff@$slug.com")
        val body = objectMapper.writeValueAsString(mapOf("orderIds" to listOf(UUID.randomUUID().toString())))

        // STAFF -> 403 (barrado pela authz antes de tocar o dominio).
        mockMvc.perform(
            post("/delivery/route/optimize").header("Authorization", "Bearer $staffToken")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isForbidden)

        // ADMIN -> passa a authz; o id ficticio nao existe -> 404 (nao 403).
        mockMvc.perform(
            post("/delivery/route/optimize").header("Authorization", "Bearer $adminToken")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isNotFound)
    }
}
