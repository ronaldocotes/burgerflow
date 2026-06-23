package com.burgerflow

import com.burgerflow.model.control.Tenant
import com.burgerflow.model.control.User
import com.burgerflow.model.control.UserRole
import com.burgerflow.repository.control.TenantRepository
import com.burgerflow.repository.control.UserRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * End-to-end HTTP slice: login -> create product with Idempotency-Key -> verify
 * the tenant binding comes from the JWT (no X-Tenant-ID header needed) and that
 * idempotency re-serves / rejects correctly.
 */
@AutoConfigureMockMvc
class ProductCrudHttpTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val objectMapper: ObjectMapper,
) : IntegrationTestBase() {

    private lateinit var slug: String

    @BeforeEach
    fun seed() {
        slug = "http_${UUID.randomUUID().toString().take(8)}"
        val tenant = tenantRepository.save(Tenant(slug = slug, displayName = "HTTP Burger"))
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
        val node = objectMapper.readTree(res.response.contentAsString)
        return node.get("token").asText()
    }

    // Fixed categoryId per SKU so an identical SKU yields an identical payload
    // (and therefore an identical idempotency hash).
    private val fixedCategory = UUID.randomUUID().toString()

    private fun productBody(sku: String) = objectMapper.writeValueAsString(
        mapOf("categoryId" to fixedCategory, "sku" to sku, "name" to "X-Tudo", "priceCents" to 4200),
    )

    @Test
    fun `create product then list it - tenant resolved from JWT`() {
        val token = login()
        val key = UUID.randomUUID().toString()

        mockMvc.perform(
            post("/products")
                .header("Authorization", "Bearer $token")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(productBody("X-TUDO")),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.sku").value("X-TUDO"))
            .andExpect(jsonPath("$.priceCents").value(4200))

        mockMvc.perform(get("/products").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[0].sku").value("X-TUDO"))
    }

    @Test
    fun `same idempotency key re-serves the first response`() {
        val token = login()
        val key = UUID.randomUUID().toString()

        val first = mockMvc.perform(
            post("/products").header("Authorization", "Bearer $token")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(productBody("COMBO")),
        ).andExpect(status().isCreated).andReturn()
        val firstId = objectMapper.readTree(first.response.contentAsString).get("id").asText()
        assertNotNull(firstId)

        // Same key + same payload -> re-serve identical body, no duplicate created.
        mockMvc.perform(
            post("/products").header("Authorization", "Bearer $token")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(productBody("COMBO")),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value(firstId))
    }

    @Test
    fun `same idempotency key with different payload is rejected with 409`() {
        val token = login()
        val key = UUID.randomUUID().toString()

        mockMvc.perform(
            post("/products").header("Authorization", "Bearer $token")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(productBody("FIRST")),
        ).andExpect(status().isCreated)

        mockMvc.perform(
            post("/products").header("Authorization", "Bearer $token")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(productBody("DIFFERENT")),
        ).andExpect(status().isConflict)
    }

    @Test
    fun `create product without Idempotency-Key is rejected`() {
        val token = login()
        mockMvc.perform(
            post("/products").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(productBody("NOKEY")),
        ).andExpect(status().isBadRequest)
    }
}
