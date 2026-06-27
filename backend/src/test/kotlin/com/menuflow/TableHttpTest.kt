package com.menuflow

import com.fasterxml.jackson.databind.ObjectMapper
import com.menuflow.model.Order
import com.menuflow.model.OrderStatus
import com.menuflow.model.control.Tenant
import com.menuflow.model.control.User
import com.menuflow.model.control.UserRole
import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.control.UserRepository
import com.menuflow.repository.tenant.OrderRepository
import com.menuflow.repository.tenant.TableSessionRepository
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * HTTP slice do módulo Mesas e Comandas. Login MANAGER -> cria mesa -> abre/
 * pede-conta/fecha comanda; valida o ciclo OPEN->BILLING->CLOSED, o bloqueio de
 * fechar com pedido na cozinha (400) e a unicidade da comanda ativa (409).
 */
@AutoConfigureMockMvc
class TableHttpTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val objectMapper: ObjectMapper,
    private val sessionRepository: TableSessionRepository,
    private val orderRepository: OrderRepository,
    private val tenantTestTx: TenantTestTx,
) : IntegrationTestBase() {

    private lateinit var slug: String

    @BeforeEach
    fun seed() {
        slug = "tbl_${UUID.randomUUID().toString().take(8)}"
        val tenant = tenantRepository.save(Tenant(slug = slug, displayName = "Mesa Burger"))
        userRepository.save(
            User(
                tenantId = tenant.id!!,
                email = "mgr@$slug.com",
                passwordHash = passwordEncoder.encode("pass1234"),
                firstName = "Manager",
                role = UserRole.MANAGER,
            ),
        )
    }

    private fun login(): String {
        val body = objectMapper.writeValueAsString(
            mapOf("email" to "mgr@$slug.com", "password" to "pass1234", "tenantSlug" to slug),
        )
        val res = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isOk).andReturn()
        return objectMapper.readTree(res.response.contentAsString).get("token").asText()
    }

    private fun createTable(token: String, label: String): String {
        val body = objectMapper.writeValueAsString(mapOf("label" to label, "seats" to 4))
        val res = mockMvc.perform(
            post("/tables").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isCreated).andReturn()
        return objectMapper.readTree(res.response.contentAsString).get("id").asText()
    }

    /** Persiste um pedido PENDING vinculado à comanda ativa da mesa (fora do HTTP). */
    private fun seedPendingOrder(tableId: UUID) {
        TenantContext.set(slug)
        try {
            tenantTestTx.run {
                val session = sessionRepository.findActiveByTableId(tableId).orElseThrow()
                orderRepository.save(
                    Order(
                        orderNumber = "T-${UUID.randomUUID().toString().take(8)}",
                        status = OrderStatus.PENDING,
                        subtotalCents = 1000,
                        totalCents = 1000,
                        tableSession = session,
                    ),
                )
            }
        } finally {
            TenantContext.clear()
        }
    }

    @Test
    fun `create table returns 201`() {
        val token = login()
        mockMvc.perform(
            post("/tables").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("label" to "Mesa 1", "seats" to 6))),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.label").value("Mesa 1"))
            .andExpect(jsonPath("$.seats").value(6))
            .andExpect(jsonPath("$.active").value(true))
            .andExpect(jsonPath("$.session").doesNotExist())
    }

    @Test
    fun `open session returns OPEN`() {
        val token = login()
        val tableId = createTable(token, "Mesa 2")
        mockMvc.perform(
            post("/tables/$tableId/session/open").header("Authorization", "Bearer $token"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.session.status").value("OPEN"))
            .andExpect(jsonPath("$.session.sessionId").exists())
    }

    @Test
    fun `request bill transitions to BILLING`() {
        val token = login()
        val tableId = createTable(token, "Mesa 3")
        mockMvc.perform(post("/tables/$tableId/session/open").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
        mockMvc.perform(post("/tables/$tableId/session/bill").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.session.status").value("BILLING"))
            .andExpect(jsonPath("$.session.billRequestedAt").exists())
    }

    @Test
    fun `close with pending kitchen order returns 400`() {
        val token = login()
        val tableId = createTable(token, "Mesa 4")
        mockMvc.perform(post("/tables/$tableId/session/open").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)

        seedPendingOrder(UUID.fromString(tableId))

        mockMvc.perform(post("/tables/$tableId/session/close").header("Authorization", "Bearer $token"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("Há pedidos em aberto na cozinha"))
    }

    @Test
    fun `close clean session returns 200 CLOSED`() {
        val token = login()
        val tableId = createTable(token, "Mesa 5")
        mockMvc.perform(post("/tables/$tableId/session/open").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
        mockMvc.perform(post("/tables/$tableId/session/close").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.session.status").value("CLOSED"))
    }

    @Test
    fun `opening a second session on same table returns 409`() {
        val token = login()
        val tableId = createTable(token, "Mesa 6")
        mockMvc.perform(post("/tables/$tableId/session/open").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
        mockMvc.perform(post("/tables/$tableId/session/open").header("Authorization", "Bearer $token"))
            .andExpect(status().isConflict)
    }

    @Test
    fun `reopening after a clean close is allowed`() {
        val token = login()
        val tableId = createTable(token, "Mesa 7")
        mockMvc.perform(post("/tables/$tableId/session/open").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
        mockMvc.perform(post("/tables/$tableId/session/close").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
        // Mesa livre de novo: nova comanda pode abrir.
        mockMvc.perform(post("/tables/$tableId/session/open").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.session.status").value("OPEN"))
    }
}
