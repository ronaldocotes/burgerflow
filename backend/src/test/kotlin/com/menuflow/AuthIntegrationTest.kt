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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@AutoConfigureMockMvc
class AuthIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val objectMapper: ObjectMapper,
) : IntegrationTestBase() {

    @BeforeEach
    fun seed() {
        userRepository.deleteAll()
        tenantRepository.deleteAll()
        val tenant = tenantRepository.save(Tenant(slug = "alpha", displayName = "Alpha Burger"))
        userRepository.save(
            User(
                tenantId = tenant.id!!,
                email = "owner@alpha.com",
                passwordHash = passwordEncoder.encode("secret123"),
                firstName = "Owner",
                role = UserRole.ADMIN,
            ),
        )
    }

    private fun loginBody(email: String, password: String, slug: String) =
        objectMapper.writeValueAsString(mapOf("email" to email, "password" to password, "tenantSlug" to slug))

    @Test
    fun `valid login returns access and refresh tokens`() {
        mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("owner@alpha.com", "secret123", "alpha")),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.token").isNotEmpty)
            .andExpect(jsonPath("$.refreshToken").isNotEmpty)
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresIn").value(3600))
    }

    @Test
    fun `wrong password is rejected with 401`() {
        mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("owner@alpha.com", "WRONG", "alpha")),
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `right credentials but wrong tenant is rejected with 401`() {
        mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("owner@alpha.com", "secret123", "beta")),
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `protected route without token returns 401`() {
        mockMvc.perform(post("/products").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized)
    }
}
