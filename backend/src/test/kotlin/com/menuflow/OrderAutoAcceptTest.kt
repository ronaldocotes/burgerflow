package com.menuflow

import com.fasterxml.jackson.databind.ObjectMapper
import com.menuflow.dto.OrderCreateRequest
import com.menuflow.dto.OrderItemRequest
import com.menuflow.dto.ProductCreateRequest
import com.menuflow.model.OrderStatus
import com.menuflow.model.control.Tenant
import com.menuflow.model.control.User
import com.menuflow.model.control.UserRole
import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.control.UserRepository
import com.menuflow.repository.tenant.TenantConfigRepository
import com.menuflow.service.OrderService
import com.menuflow.service.ProductService
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Aceite automático de pedidos (TenantConfig.autoAcceptOrders).
 *
 *  - Nível serviço: com o flag desligado o pedido nasce PENDING; ligado, nasce
 *    PREPARING (vai direto para a cozinha).
 *  - Nível HTTP: GET /config devolve o valor; PATCH /config (ADMIN/MANAGER)
 *    atualiza; STAFF não pode escrever (403).
 *
 * Cada caso usa seu PRÓPRIO tenant (db isolado) para não cruzar config/estoque.
 */
@AutoConfigureMockMvc
class OrderAutoAcceptTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val objectMapper: ObjectMapper,
    private val orderService: OrderService,
    private val productService: ProductService,
    private val tenantConfigRepository: TenantConfigRepository,
    private val tenantTx: TenantTestTx,
) : IntegrationTestBase() {

    @AfterEach
    fun clear() = TenantContext.clear()

    /** Cria um produto simples (sem ficha técnica) no tenant já bound. */
    private fun seedProduct(tenant: String): UUID {
        TenantContext.set(tenant)
        return productService.create(
            ProductCreateRequest(
                categoryId = UUID.randomUUID(),
                sku = "P-${UUID.randomUUID().toString().take(6)}",
                name = "Burger",
                priceCents = 3000,
            ),
        ).id
    }

    /** Liga o aceite automático na linha de config (semeada pela V13) do tenant. */
    private fun enableAutoAccept(tenant: String) {
        TenantContext.set(tenant)
        tenantTx.run {
            TenantContext.set(tenant)
            val config = tenantConfigRepository.findFirstByOrderByCreatedAtAsc()!!
            config.autoAcceptOrders = true
            tenantConfigRepository.save(config)
        }
    }

    @Test
    fun `auto-accept off creates order in PENDING`() {
        val tenant = "acc_off_${UUID.randomUUID().toString().take(8)}"
        val productId = seedProduct(tenant)

        TenantContext.set(tenant)
        val order = orderService.create(
            OrderCreateRequest(items = listOf(OrderItemRequest(productId = productId, quantity = 1))),
            userId = null,
        )

        assertEquals(OrderStatus.PENDING, order.status)
    }

    @Test
    fun `auto-accept on creates order in PREPARING`() {
        val tenant = "acc_on_${UUID.randomUUID().toString().take(8)}"
        val productId = seedProduct(tenant)
        enableAutoAccept(tenant)

        TenantContext.set(tenant)
        val order = orderService.create(
            OrderCreateRequest(items = listOf(OrderItemRequest(productId = productId, quantity = 1))),
            userId = null,
        )

        assertEquals(OrderStatus.PREPARING, order.status)
    }

    // --- HTTP: GET/PATCH /config ---------------------------------------------

    private fun seedTenantWithUsers(): String {
        val slug = "cfg_${UUID.randomUUID().toString().take(8)}"
        val tenant = tenantRepository.save(Tenant(slug = slug, displayName = "Config Burger"))
        userRepository.save(
            User(
                tenantId = tenant.id!!,
                email = "mgr@$slug.com",
                passwordHash = passwordEncoder.encode("pass1234"),
                firstName = "Manager",
                role = UserRole.MANAGER,
            ),
        )
        userRepository.save(
            User(
                tenantId = tenant.id!!,
                email = "staff@$slug.com",
                passwordHash = passwordEncoder.encode("pass1234"),
                firstName = "Staff",
                role = UserRole.STAFF,
            ),
        )
        return slug
    }

    private fun login(slug: String, email: String): String {
        val body = objectMapper.writeValueAsString(
            mapOf("email" to email, "password" to "pass1234", "tenantSlug" to slug),
        )
        val res = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isOk).andReturn()
        return objectMapper.readTree(res.response.contentAsString).get("token").asText()
    }

    @Test
    fun `GET config returns default false then reflects update`() {
        val slug = seedTenantWithUsers()
        val token = login(slug, "mgr@$slug.com")

        mockMvc.perform(get("/config").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.autoAcceptOrders").value(false))

        mockMvc.perform(
            patch("/config").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("autoAcceptOrders" to true))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.autoAcceptOrders").value(true))

        mockMvc.perform(get("/config").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.autoAcceptOrders").value(true))
    }

    @Test
    fun `PATCH config by MANAGER returns 200`() {
        val slug = seedTenantWithUsers()
        val token = login(slug, "mgr@$slug.com")

        mockMvc.perform(
            patch("/config").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("autoAcceptOrders" to true))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.autoAcceptOrders").value(true))
    }

    @Test
    fun `PATCH config by STAFF is forbidden`() {
        val slug = seedTenantWithUsers()
        val token = login(slug, "staff@$slug.com")

        mockMvc.perform(
            patch("/config").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("autoAcceptOrders" to true))),
        )
            .andExpect(status().isForbidden)
    }
}
