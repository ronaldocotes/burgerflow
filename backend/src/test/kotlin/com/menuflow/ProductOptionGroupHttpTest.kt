package com.menuflow

import com.fasterxml.jackson.databind.ObjectMapper
import com.menuflow.model.control.Tenant
import com.menuflow.model.control.User
import com.menuflow.model.control.UserRole
import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.control.UserRepository
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
 * HTTP slice dos complementos (option groups). Login MANAGER -> cria produto ->
 * cria grupo -> adiciona opção -> lista aninhado; valida regra de seleção.
 */
@AutoConfigureMockMvc
class ProductOptionGroupHttpTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val objectMapper: ObjectMapper,
) : IntegrationTestBase() {

    private lateinit var slug: String

    @BeforeEach
    fun seed() {
        slug = "opt_${UUID.randomUUID().toString().take(8)}"
        val tenant = tenantRepository.save(Tenant(slug = slug, displayName = "Opt Burger"))
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

    private fun createProduct(token: String): String {
        val body = objectMapper.writeValueAsString(
            mapOf(
                "categoryId" to UUID.randomUUID().toString(),
                "sku" to "BURG-${UUID.randomUUID().toString().take(6)}",
                "name" to "X-Burger",
                "priceCents" to 3000,
            ),
        )
        val res = mockMvc.perform(
            post("/products")
                .header("Authorization", "Bearer $token")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isCreated).andReturn()
        return objectMapper.readTree(res.response.contentAsString).get("id").asText()
    }

    @Test
    fun `create option group with options then list nested`() {
        val token = login()
        val productId = createProduct(token)

        val groupBody = objectMapper.writeValueAsString(
            mapOf("name" to "Adicionais", "minSelect" to 0, "maxSelect" to 3),
        )
        val groupRes = mockMvc.perform(
            post("/products/$productId/option-groups")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(groupBody),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.name").value("Adicionais"))
            .andExpect(jsonPath("$.required").value(false))
            .andReturn()
        val groupId = objectMapper.readTree(groupRes.response.contentAsString).get("id").asText()

        val optBody = objectMapper.writeValueAsString(mapOf("name" to "Bacon", "priceCents" to 300))
        mockMvc.perform(
            post("/products/$productId/option-groups/$groupId/options")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(optBody),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.name").value("Bacon"))
            .andExpect(jsonPath("$.priceCents").value(300))

        mockMvc.perform(
            get("/products/$productId/option-groups").header("Authorization", "Bearer $token"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].name").value("Adicionais"))
            .andExpect(jsonPath("$[0].options[0].name").value("Bacon"))
            .andExpect(jsonPath("$[0].options[0].priceCents").value(300))
    }

    @Test
    fun `group with minSelect greater than maxSelect is rejected`() {
        val token = login()
        val productId = createProduct(token)
        val body = objectMapper.writeValueAsString(
            mapOf("name" to "Invalido", "minSelect" to 3, "maxSelect" to 1),
        )
        mockMvc.perform(
            post("/products/$productId/option-groups")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `required group reports required true when minSelect is positive`() {
        val token = login()
        val productId = createProduct(token)
        val body = objectMapper.writeValueAsString(
            mapOf("name" to "Ponto da carne", "minSelect" to 1, "maxSelect" to 1),
        )
        mockMvc.perform(
            post("/products/$productId/option-groups")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.required").value(true))
    }
}
