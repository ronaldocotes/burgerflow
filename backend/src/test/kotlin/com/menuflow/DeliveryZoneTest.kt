package com.menuflow

import com.fasterxml.jackson.databind.ObjectMapper
import com.menuflow.delivery.DeliveryZoneResolver
import com.menuflow.dto.DeliveryZoneUpsert
import com.menuflow.dto.DeliveryZonesRequest
import com.menuflow.dto.OrderCreateRequest
import com.menuflow.dto.OrderItemRequest
import com.menuflow.dto.ProductCreateRequest
import com.menuflow.dto.TenantConfigUpdateRequest
import com.menuflow.exception.BusinessException
import com.menuflow.model.DeliveryZone
import com.menuflow.model.OrderType
import com.menuflow.model.control.Tenant
import com.menuflow.model.control.User
import com.menuflow.model.control.UserRole
import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.control.UserRepository
import com.menuflow.service.DeliveryZoneService
import com.menuflow.service.OrderService
import com.menuflow.service.ProductService
import com.menuflow.service.TenantConfigService
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Issue #2 — zonas de entrega por raio (Haversine, decisao D-1). Prova:
 *  1. o resolver escolhe a zona certa por distancia em linha reta (0.5km->1km,
 *     1.5km->2km, 5km->fora);
 *  2. frete gratis por zona (is_free) e por subtotal (limiar global);
 *  3. pedido DELIVERY com zonas + ponto FORA -> rejeitado (nao aceita frete do cliente),
 *     e ponto DENTRO -> frete da zona ignorando o deliveryFeeCents do cliente;
 *  4. tenant SEM zonas -> mantem o calculo flat/legado (sem regressao);
 *  5. RBAC: STAFF nao acessa o CRUD (403); ADMIN acessa;
 *  6. validacao server-side (raio decrescente, fee negativo, ETA invertida -> 400).
 *
 * Distancia: 1 grau de latitude ~ 111.19 km (Haversine, R=6371). Origem em (0,0);
 * pontos deslocados so em latitude para distancias conhecidas.
 */
@AutoConfigureMockMvc
class DeliveryZoneTest @Autowired constructor(
    private val resolver: DeliveryZoneResolver,
    private val deliveryZoneService: DeliveryZoneService,
    private val orderService: OrderService,
    private val productService: ProductService,
    private val tenantConfigService: TenantConfigService,
    private val mockMvc: MockMvc,
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val objectMapper: ObjectMapper,
) : IntegrationTestBase() {

    @AfterEach
    fun clear() = TenantContext.clear()

    private fun bind(): String {
        val tenant = "zone_${UUID.randomUUID().toString().take(8)}"
        TenantContext.set(tenant)
        return tenant
    }

    private fun newProduct(price: Long = 2_000): UUID =
        productService.create(
            ProductCreateRequest(
                categoryId = UUID.randomUUID(),
                sku = "ZONE-${UUID.randomUUID().toString().take(6)}",
                name = "Burger",
                priceCents = price,
            ),
        ).id

    // Aneis em memoria (contrato: ordenados por raio crescente).
    private fun rings(z1Free: Boolean = false) = listOf(
        DeliveryZone(name = "1km", maxRadiusKm = 1.0, feeCents = 500, etaMinMinutes = 10, etaMaxMinutes = 20, isFree = z1Free),
        DeliveryZone(name = "2km", maxRadiusKm = 2.0, feeCents = 800, etaMinMinutes = 15, etaMaxMinutes = 25),
    )

    // --- 1. resolver escolhe a zona por distancia Haversine ---
    @Test
    fun `resolver picks the smallest ring covering the straight-line distance`() {
        val zones = rings()
        // ~0.5km -> zona de 1km (500).
        val near = resolver.resolve(0.0, 0.0, 0.0045, 0.0, 2_000, null, zones)
        assertNotNull(near)
        assertEquals(500, near!!.feeCents)
        assertEquals("1km", near.zoneName)

        // ~1.5km -> zona de 2km (800).
        val mid = resolver.resolve(0.0, 0.0, 0.0135, 0.0, 2_000, null, zones)
        assertNotNull(mid)
        assertEquals(800, mid!!.feeCents)
        assertEquals("2km", mid.zoneName)

        // ~5km -> fora de todas as zonas.
        val far = resolver.resolve(0.0, 0.0, 0.045, 0.0, 2_000, null, zones)
        assertNull(far, "ponto alem do maior anel deve ser fora de area (null)")
    }

    // --- 2. frete gratis por zona e por subtotal ---
    @Test
    fun `free by ring flag and free by subtotal threshold`() {
        // Zona 1 marcada is_free -> fee 0 mesmo com feeCents=500.
        val byRing = resolver.resolve(0.0, 0.0, 0.0045, 0.0, 2_000, null, rings(z1Free = true))
        assertNotNull(byRing)
        assertEquals(0, byRing!!.feeCents)
        assertTrue(byRing.isFree)

        // Limiar global: subtotal 6000 >= 5000 -> frete gratis mesmo na zona paga (2km).
        val byThreshold = resolver.resolve(0.0, 0.0, 0.0135, 0.0, 6_000, 5_000, rings())
        assertNotNull(byThreshold)
        assertEquals(0, byThreshold!!.feeCents)
        assertTrue(byThreshold.isFree)

        // Subtotal abaixo do limiar -> paga a taxa da zona.
        val below = resolver.resolve(0.0, 0.0, 0.0135, 0.0, 4_999, 5_000, rings())
        assertEquals(800, below!!.feeCents)
    }

    // --- 3. pedido DELIVERY com zonas: dentro cobra a zona; fora rejeita ---
    @Test
    fun `delivery order with zones charges the ring fee and blocks out-of-area`() {
        bind()
        tenantConfigService.update(
            TenantConfigUpdateRequest(autoAcceptOrders = false, restaurantLat = 0.0, restaurantLng = 0.0),
        )
        deliveryZoneService.replace(
            DeliveryZonesRequest(
                zones = listOf(
                    DeliveryZoneUpsert(name = "1km", maxRadiusKm = 1.0, feeCents = 500, etaMinMinutes = 10, etaMaxMinutes = 20),
                    DeliveryZoneUpsert(name = "2km", maxRadiusKm = 2.0, feeCents = 800, etaMinMinutes = 15, etaMaxMinutes = 25),
                ),
            ),
        )
        val productId = newProduct()

        // Dentro (~0.5km): frete = zona (500), IGNORANDO o deliveryFeeCents=9999 do cliente.
        val inArea = orderService.create(
            OrderCreateRequest(
                orderType = OrderType.DELIVERY,
                deliveryFeeCents = 9_999,
                items = listOf(OrderItemRequest(productId = productId, quantity = 1)),
                delivery = com.menuflow.dto.DeliveryAddressRequest(lat = 0.0045, lng = 0.0),
            ),
            null,
        )
        assertEquals(500, inArea.deliveryFeeCents, "frete deve vir da zona, nao do cliente")

        // Fora (~5km): pedido bloqueado (nao aceita frete arbitrario do cliente).
        val ex = assertThrows(BusinessException::class.java) {
            orderService.create(
                OrderCreateRequest(
                    orderType = OrderType.DELIVERY,
                    deliveryFeeCents = 0,
                    items = listOf(OrderItemRequest(productId = productId, quantity = 1)),
                    delivery = com.menuflow.dto.DeliveryAddressRequest(lat = 0.045, lng = 0.0),
                ),
                null,
            )
        }
        assertTrue(ex.message!!.contains("fora da área", ignoreCase = true))
    }

    // --- 4. tenant SEM zonas -> calculo flat/legado (sem regressao) ---
    @Test
    fun `tenant without zones keeps the legacy flat fee from the request`() {
        bind()
        val productId = newProduct()
        // Sem zonas e sem coordenadas de origem: mantem o fee do request (fluxo legado).
        val order = orderService.create(
            OrderCreateRequest(
                orderType = OrderType.DELIVERY,
                deliveryFeeCents = 700,
                items = listOf(OrderItemRequest(productId = productId, quantity = 1)),
            ),
            null,
        )
        assertEquals(700, order.deliveryFeeCents, "sem zonas, o fee legado do request deve passar")
    }

    // --- 6. validacao server-side (400 via IllegalArgumentException) ---
    @Test
    fun `replace rejects decreasing radius, negative fee and inverted eta`() {
        bind()
        // Raios decrescentes/sobrepostos.
        assertThrows(IllegalArgumentException::class.java) {
            deliveryZoneService.replace(
                DeliveryZonesRequest(
                    zones = listOf(
                        DeliveryZoneUpsert(maxRadiusKm = 2.0, feeCents = 500, etaMinMinutes = 10, etaMaxMinutes = 20),
                        DeliveryZoneUpsert(maxRadiusKm = 1.0, feeCents = 800, etaMinMinutes = 10, etaMaxMinutes = 20),
                    ),
                ),
            )
        }
        // Fee negativo.
        assertThrows(IllegalArgumentException::class.java) {
            deliveryZoneService.replace(
                DeliveryZonesRequest(
                    zones = listOf(DeliveryZoneUpsert(maxRadiusKm = 1.0, feeCents = -1, etaMinMinutes = 10, etaMaxMinutes = 20)),
                ),
            )
        }
        // ETA invertida.
        assertThrows(IllegalArgumentException::class.java) {
            deliveryZoneService.replace(
                DeliveryZonesRequest(
                    zones = listOf(DeliveryZoneUpsert(maxRadiusKm = 1.0, feeCents = 500, etaMinMinutes = 30, etaMaxMinutes = 10)),
                ),
            )
        }
    }

    @Test
    fun `replace persists rings and free-order threshold and is idempotent`() {
        bind()
        val req = DeliveryZonesRequest(
            zones = listOf(
                DeliveryZoneUpsert(name = "Centro", maxRadiusKm = 1.0, feeCents = 500, etaMinMinutes = 10, etaMaxMinutes = 20),
                DeliveryZoneUpsert(maxRadiusKm = 2.5, feeCents = 800, etaMinMinutes = 15, etaMaxMinutes = 25, isFree = true),
            ),
            freeDeliveryMinOrderCents = 8_000,
        )
        deliveryZoneService.replace(req)
        // Reenvio do mesmo conjunto converge (idempotente): continua com 2 zonas.
        val out = deliveryZoneService.replace(req)
        assertEquals(2, out.zones.size)
        assertEquals(8_000, out.freeDeliveryMinOrderCents)
        assertEquals(0, out.zones[0].displayOrder)
        assertTrue(out.zones[1].isFree)
        // Releitura pelo GET.
        val got = deliveryZoneService.get()
        assertEquals(2, got.zones.size)
        assertEquals(8_000, got.freeDeliveryMinOrderCents)
    }

    // --- 5. RBAC HTTP: STAFF 403; ADMIN 200 ---
    @Test
    fun `zone config CRUD requires ADMIN or MANAGER`() {
        val slug = "zonehttp_${UUID.randomUUID().toString().take(8)}"
        val tenant = tenantRepository.save(Tenant(slug = slug, displayName = "Zone Http Burger"))
        userRepository.save(
            User(tenantId = tenant.id!!, email = "admin@$slug.com", passwordHash = passwordEncoder.encode("pass1234"), firstName = "Admin", role = UserRole.ADMIN),
        )
        userRepository.save(
            User(tenantId = tenant.id!!, email = "staff@$slug.com", passwordHash = passwordEncoder.encode("pass1234"), firstName = "Staff", role = UserRole.STAFF),
        )

        fun login(email: String): String {
            val body = objectMapper.writeValueAsString(mapOf("email" to email, "password" to "pass1234", "tenantSlug" to slug))
            val res = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk).andReturn()
            return objectMapper.readTree(res.response.contentAsString).get("token").asText()
        }

        val adminToken = login("admin@$slug.com")
        val staffToken = login("staff@$slug.com")
        val body = objectMapper.writeValueAsString(
            mapOf(
                "zones" to listOf(
                    mapOf("name" to "1km", "maxRadiusKm" to 1.0, "feeCents" to 500, "etaMinMinutes" to 10, "etaMaxMinutes" to 20, "isFree" to false),
                ),
                "freeDeliveryMinOrderCents" to null,
            ),
        )

        // STAFF -> 403 no GET e no PUT.
        mockMvc.perform(get("/delivery/zones").header("Authorization", "Bearer $staffToken"))
            .andExpect(status().isForbidden)
        mockMvc.perform(put("/delivery/zones").header("Authorization", "Bearer $staffToken").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden)

        // ADMIN -> 200.
        mockMvc.perform(put("/delivery/zones").header("Authorization", "Bearer $adminToken").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk)
        mockMvc.perform(get("/delivery/zones").header("Authorization", "Bearer $adminToken"))
            .andExpect(status().isOk)
    }
}
