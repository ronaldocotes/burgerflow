package com.menuflow

import com.menuflow.model.control.Tenant
import com.menuflow.model.control.User
import com.menuflow.model.control.UserRole
import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.control.UserRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Refresh-token revocation (Sprint 2): logout revokes the token, rotation
 * invalidates the old one. A revoked/rotated refresh token is rejected with 401
 * even though its JWT signature/expiry are still valid.
 */
@AutoConfigureMockMvc
class RefreshRevocationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val objectMapper: ObjectMapper,
) : IntegrationTestBase() {

    private lateinit var slug: String

    @BeforeEach
    fun seed() {
        slug = "rev_${UUID.randomUUID().toString().take(8)}"
        val tenant = tenantRepository.save(Tenant(slug = slug, displayName = "Rev Burger"))
        userRepository.save(
            User(
                tenantId = tenant.id!!,
                email = "u@$slug.com",
                passwordHash = passwordEncoder.encode("pass1234"),
                firstName = "U",
                role = UserRole.ADMIN,
            ),
        )
    }

    private fun login(): Pair<String, String> {
        val body = objectMapper.writeValueAsString(
            mapOf("email" to "u@$slug.com", "password" to "pass1234", "tenantSlug" to slug),
        )
        val res = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk).andReturn()
        val node = objectMapper.readTree(res.response.contentAsString)
        return node.get("token").asText() to node.get("refreshToken").asText()
    }

    private fun refreshBody(refreshToken: String) =
        objectMapper.writeValueAsString(mapOf("refreshToken" to refreshToken))

    @Test
    fun `valid refresh issues new tokens`() {
        val (_, refresh) = login()
        mockMvc.perform(
            post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(refreshBody(refresh)),
        ).andExpect(status().isOk)
    }

    @Test
    fun `logout revokes the refresh token - subsequent refresh is 401`() {
        val (_, refresh) = login()

        mockMvc.perform(
            post("/auth/logout").contentType(MediaType.APPLICATION_JSON).content(refreshBody(refresh)),
        ).andExpect(status().isNoContent)

        mockMvc.perform(
            post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(refreshBody(refresh)),
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `rotated refresh token cannot be reused`() {
        val (_, refresh) = login()

        // First refresh rotates the token (old is revoked).
        mockMvc.perform(
            post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(refreshBody(refresh)),
        ).andExpect(status().isOk)

        // Reusing the original refresh token now fails.
        mockMvc.perform(
            post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(refreshBody(refresh)),
        ).andExpect(status().isUnauthorized)
    }
}
