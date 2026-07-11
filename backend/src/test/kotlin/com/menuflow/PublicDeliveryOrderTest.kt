package com.menuflow

import com.fasterxml.jackson.databind.ObjectMapper
import com.menuflow.dispatch.GeocodingService
import com.menuflow.dispatch.LatLng
import com.menuflow.dto.DeliveryZoneUpsert
import com.menuflow.dto.DeliveryZonesRequest
import com.menuflow.dto.ProductCreateRequest
import com.menuflow.dto.TenantConfigUpdateRequest
import com.menuflow.model.control.Tenant
import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.tenant.OrderRepository
import com.menuflow.service.DeliveryZoneService
import com.menuflow.service.ProductService
import com.menuflow.service.TenantConfigService
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Fase 1 (backend) — delivery no cardapio PUBLICO. Prova o fluxo anonimo:
 *  1. DELIVERY com endereco em zona -> pedido criado com frete da zona no total +
 *     resposta traz feeCents/eta;
 *  2. DELIVERY fora de area -> 422, NADA persistido;
 *  3. DELIVERY sem geocode (GeocodingService null) -> 422;
 *  4. PICKUP (=TAKEAWAY) -> sem frete;
 *  5. DINE_IN via tableLabel -> inalterado (sem regressao);
 *  6. delivery-quote em zona -> feeCents+eta; fora -> 422;
 *  7. orderType invalido -> 400; endereco ausente em DELIVERY -> 400.
 *
 * GeocodingService e MOCKADO: em teste nao ha GOOGLE_ROUTES_API_KEY (geocode real
 * retornaria null sempre), e o publico NAO envia lat/lng — entao a resolucao de zona
 * depende do geocode server-side, que aqui e controlado por stub. Origem do
 * restaurante em (0,0); pontos deslocados so em latitude (1 grau ~ 111km).
 * NAO e @Transactional: os saves comitam para o create/quote enxergarem os dados.
 */
@AutoConfigureMockMvc
class PublicDeliveryOrderTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val tenantRepository: TenantRepository,
    private val productService: ProductService,
    private val tenantConfigService: TenantConfigService,
    private val deliveryZoneService: DeliveryZoneService,
    private val orderRepository: OrderRepository,
    private val objectMapper: ObjectMapper,
) : IntegrationTestBase() {

    @MockitoBean
    private lateinit var geocodingService: GeocodingService

    @AfterEach
    fun clear() = TenantContext.clear()

    private fun <T> anyArg(): T = Mockito.any()

    /** Coordenada geocodada para o proximo request publico (o publico nao envia pin). */
    private fun stubGeocode(point: LatLng?) {
        Mockito.`when`(geocodingService.geocode(anyArg(), anyArg(), anyArg(), anyArg())).thenReturn(point)
    }

    /**
     * Provisiona tenant (controle) + config (com/sem coords) + zonas (opcional) + 1
     * produto no banco do tenant. Retorna (slug, productId). Zonas: 1km->500 (10-20min),
     * 2km->800 (15-25min).
     */
    private fun setup(withZones: Boolean = true, restaurantLocated: Boolean = true, price: Long = 2_000): Pair<String, UUID> {
        val slug = "pubdel_${UUID.randomUUID().toString().take(8)}"
        tenantRepository.save(Tenant(slug = slug, displayName = "Public Delivery Burger"))
        TenantContext.set(slug)
        tenantConfigService.update(
            TenantConfigUpdateRequest(
                autoAcceptOrders = false,
                restaurantLat = if (restaurantLocated) 0.0 else null,
                restaurantLng = if (restaurantLocated) 0.0 else null,
            ),
        )
        if (withZones) {
            deliveryZoneService.replace(
                DeliveryZonesRequest(
                    zones = listOf(
                        DeliveryZoneUpsert(name = "1km", maxRadiusKm = 1.0, feeCents = 500, etaMinMinutes = 10, etaMaxMinutes = 20),
                        DeliveryZoneUpsert(name = "2km", maxRadiusKm = 2.0, feeCents = 800, etaMinMinutes = 15, etaMaxMinutes = 25),
                    ),
                ),
            )
        }
        val productId = productService.create(
            ProductCreateRequest(
                categoryId = UUID.randomUUID(),
                sku = "PD-${UUID.randomUUID().toString().take(6)}",
                name = "Burger",
                priceCents = price,
            ),
        ).id
        return slug to productId
    }

    private fun orderCount(slug: String): Long {
        TenantContext.set(slug)
        return orderRepository.count()
    }

    private fun postJson(path: String, body: Map<String, Any?>) =
        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))

    // --- 1. DELIVERY em zona: pedido criado com frete da zona + resposta com fee/eta ---
    @Test
    fun `delivery order in zone is created with server-side zone fee and eta in response`() {
        val (slug, productId) = setup()
        stubGeocode(LatLng(0.0045, 0.0)) // ~0.5km -> zona de 1km (500, 10-20min)
        postJson(
            "/public/$slug/orders",
            mapOf(
                "customerName" to "Ana",
                "paymentMethod" to "CASH",
                "orderType" to "DELIVERY",
                "delivery" to mapOf("street" to "Rua A", "neighborhood" to "Centro", "city" to "Macapa", "cep" to "68900000"),
                "items" to listOf(mapOf("productId" to productId.toString(), "quantity" to 1)),
            ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.deliveryFeeCents").value(500))
            .andExpect(jsonPath("$.totalCents").value(2_500)) // 2000 + 500
            .andExpect(jsonPath("$.etaMinMinutes").value(10))
            .andExpect(jsonPath("$.etaMaxMinutes").value(20))
        assertEquals(1, orderCount(slug))
    }

    // --- 2. DELIVERY fora de area -> 422, nada persistido ---
    @Test
    fun `delivery order out of area returns 422 and persists nothing`() {
        val (slug, productId) = setup()
        stubGeocode(LatLng(0.045, 0.0)) // ~5km -> fora de todas as zonas
        postJson(
            "/public/$slug/orders",
            mapOf(
                "customerName" to "Ana",
                "paymentMethod" to "CASH",
                "orderType" to "DELIVERY",
                "delivery" to mapOf("street" to "Longe", "neighborhood" to "Zona Norte", "city" to "Macapa"),
                "items" to listOf(mapOf("productId" to productId.toString(), "quantity" to 1)),
            ),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsStringIgnoringCase("fora da área")))
        assertEquals(0, orderCount(slug), "pedido fora de area nao pode persistir")
    }

    // --- 3. DELIVERY sem geocode (null) -> 422 ---
    @Test
    fun `delivery order with no geocode returns 422`() {
        val (slug, productId) = setup()
        stubGeocode(null)
        postJson(
            "/public/$slug/orders",
            mapOf(
                "customerName" to "Ana",
                "paymentMethod" to "CASH",
                "orderType" to "DELIVERY",
                "delivery" to mapOf("street" to "Endereco Ruim", "city" to "Macapa"),
                "items" to listOf(mapOf("productId" to productId.toString(), "quantity" to 1)),
            ),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsStringIgnoringCase("localizar")))
        assertEquals(0, orderCount(slug))
    }

    // --- 4. PICKUP (=TAKEAWAY) -> sem frete ---
    @Test
    fun `pickup order has no delivery fee and needs no address`() {
        val (slug, productId) = setup()
        postJson(
            "/public/$slug/orders",
            mapOf(
                "customerName" to "Ana",
                "paymentMethod" to "CASH",
                "orderType" to "PICKUP",
                "items" to listOf(mapOf("productId" to productId.toString(), "quantity" to 1)),
            ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.deliveryFeeCents").value(0))
            .andExpect(jsonPath("$.totalCents").value(2_000))
            .andExpect(jsonPath("$.etaMinMinutes").doesNotExist())
        // Geocode nunca deve ser chamado no PICKUP.
        Mockito.verifyNoInteractions(geocodingService)
        assertEquals(1, orderCount(slug))
    }

    // --- 5. DINE_IN via tableLabel -> inalterado (compat, sem regressao) ---
    @Test
    fun `dine-in via tableLabel without orderType is unchanged and free of delivery fee`() {
        val (slug, productId) = setup()
        postJson(
            "/public/$slug/orders",
            mapOf(
                "customerName" to "Ana",
                "paymentMethod" to "CASH",
                "tableLabel" to "Mesa 5",
                "items" to listOf(mapOf("productId" to productId.toString(), "quantity" to 1)),
            ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.deliveryFeeCents").value(0))
            .andExpect(jsonPath("$.totalCents").value(2_000))
        Mockito.verifyNoInteractions(geocodingService)
        assertEquals(1, orderCount(slug))
    }

    // --- 6. delivery-quote: em zona devolve fee+eta; fora -> 422; nada persiste ---
    @Test
    fun `delivery-quote returns zone fee and eta in area and 422 out of area without persisting`() {
        val (slug, _) = setup()

        stubGeocode(LatLng(0.0135, 0.0)) // ~1.5km -> zona de 2km (800, 15-25min)
        postJson(
            "/public/$slug/delivery-quote",
            mapOf("street" to "Rua B", "neighborhood" to "Centro", "city" to "Macapa", "cep" to "68900000"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.feeCents").value(800))
            .andExpect(jsonPath("$.etaMinMinutes").value(15))
            .andExpect(jsonPath("$.etaMaxMinutes").value(25))
            .andExpect(jsonPath("$.free").value(false))

        stubGeocode(LatLng(0.045, 0.0)) // ~5km -> fora
        postJson(
            "/public/$slug/delivery-quote",
            mapOf("street" to "Longe", "city" to "Macapa"),
        )
            .andExpect(status().isUnprocessableEntity)
        assertEquals(0, orderCount(slug), "cotacao nao persiste pedido")
    }

    // --- 7. validacao: orderType invalido -> 400; DELIVERY sem endereco -> 400 ---
    @Test
    fun `invalid orderType is 400 and delivery without address is 400`() {
        val (slug, productId) = setup()

        postJson(
            "/public/$slug/orders",
            mapOf(
                "customerName" to "Ana",
                "paymentMethod" to "CASH",
                "orderType" to "FOO",
                "items" to listOf(mapOf("productId" to productId.toString(), "quantity" to 1)),
            ),
        ).andExpect(status().isBadRequest)

        postJson(
            "/public/$slug/orders",
            mapOf(
                "customerName" to "Ana",
                "paymentMethod" to "CASH",
                "orderType" to "DELIVERY",
                "items" to listOf(mapOf("productId" to productId.toString(), "quantity" to 1)),
            ),
        ).andExpect(status().isBadRequest)

        // delivery-quote sem nenhum campo de endereco -> 400.
        postJson("/public/$slug/delivery-quote", mapOf<String, Any?>("subtotalCents" to 1000))
            .andExpect(status().isBadRequest)

        assertEquals(0, orderCount(slug))
    }

    // --- A2: publico DELIVERY sem zonas ativas -> 422 (nada persistido) e NUNCA frete 0
    //         silencioso. Vale para /orders e /delivery-quote. Fail-closed. ---
    @Test
    fun `public delivery without active zones is 422 unavailable and persists nothing`() {
        // Restaurante localizado, MAS sem nenhuma zona configurada -> entrega indisponivel.
        val (slug, productId) = setup(withZones = false, restaurantLocated = true)
        // Mesmo geocodando OK, a ausencia de zonas fecha a entrega antes de criar.
        stubGeocode(LatLng(0.0045, 0.0))

        postJson(
            "/public/$slug/orders",
            mapOf(
                "customerName" to "Ana",
                "paymentMethod" to "CASH",
                "orderType" to "DELIVERY",
                "delivery" to mapOf("street" to "Rua A", "neighborhood" to "Centro", "city" to "Macapa", "cep" to "68900000"),
                "items" to listOf(mapOf("productId" to productId.toString(), "quantity" to 1)),
            ),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsStringIgnoringCase("indispon")))
        assertEquals(0, orderCount(slug), "sem zonas, o publico nao pode criar pedido com frete 0")

        // A cotacao avulsa tambem barra sem zonas (fail-closed), sem tocar o geocode.
        postJson(
            "/public/$slug/delivery-quote",
            mapOf("street" to "Rua A", "neighborhood" to "Centro", "city" to "Macapa", "cep" to "68900000"),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsStringIgnoringCase("indispon")))
        assertEquals(0, orderCount(slug))
    }

    // --- A4: pedido publico geocodado grava a ORIGEM REAL ("GOOGLE"), nao "MANUAL"
    //         (as coords foram resolvidas no servidor, nao informadas pelo cliente). ---
    @Test
    fun `public geocoded delivery order records real geocode source GOOGLE not MANUAL`() {
        val (slug, productId) = setup()
        stubGeocode(LatLng(0.0045, 0.0)) // ~0.5km -> zona de 1km
        postJson(
            "/public/$slug/orders",
            mapOf(
                "customerName" to "Ana",
                "paymentMethod" to "CASH",
                "orderType" to "DELIVERY",
                "delivery" to mapOf("street" to "Rua A", "neighborhood" to "Centro", "city" to "Macapa", "cep" to "68900000"),
                "items" to listOf(mapOf("productId" to productId.toString(), "quantity" to 1)),
            ),
        ).andExpect(status().isOk)

        TenantContext.set(slug)
        val order = orderRepository.findAll().first()
        assertEquals("GOOGLE", order.deliveryGeocodeSource, "origem publica geocodada deve ser GOOGLE, nao MANUAL")
        assertEquals(0.0045, order.deliveryLat!!, 1e-9)
        assertEquals(0.0, order.deliveryLng!!, 1e-9)
    }
}
