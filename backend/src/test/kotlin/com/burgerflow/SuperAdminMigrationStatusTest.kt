package com.burgerflow

import com.burgerflow.model.control.Tenant
import com.burgerflow.model.control.User
import com.burgerflow.model.control.UserRole
import com.burgerflow.repository.control.TenantRepository
import com.burgerflow.repository.control.UserRepository
import com.fasterxml.jackson.databind.ObjectMapper
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
 * Proves the platform drift-check endpoint (GET /admin/tenants/migration-status)
 * is reachable by SUPER_ADMIN and fail-closed for everyone else. This closes the
 * gap the Curador left: the endpoint was gated to SUPER_ADMIN, a role that did
 * not exist until now (so it was reachable by NOBODY).
 */
@AutoConfigureMockMvc
class SuperAdminMigrationStatusTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val objectMapper: ObjectMapper,
) : IntegrationTestBase() {

    private lateinit var slug: String

    @BeforeEach
    fun seed() {
        slug = "sa_${UUID.randomUUID().toString().take(8)}"
        val tenant = tenantRepository.save(Tenant(slug = slug, displayName = "SA Burger"))
        userRepository.save(
            User(
                tenantId = tenant.id!!,
                email = "root@$slug.com",
                passwordHash = passwordEncoder.encode("pass1234"),
                firstName = "Root",
                role = UserRole.SUPER_ADMIN,
            ),
        )
        userRepository.save(
            User(
                tenantId = tenant.id!!,
                email = "admin@$slug.com",
                passwordHash = passwordEncoder.encode("pass1234"),
                firstName = "Admin",
                role = UserRole.ADMIN,
            ),
        )
    }

    private fun login(email: String): String {
        val body = objectMapper.writeValueAsString(
            mapOf("email" to email, "password" to "pass1234", "tenantSlug" to slug),
        )
        val res = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk).andReturn()
        return objectMapper.readTree(res.response.contentAsString).get("token").asText()
    }

    @Test
    fun `super admin can read the cross-tenant migration status`() {
        val token = login("root@$slug.com")
        mockMvc.perform(
            get("/admin/tenants/migration-status").header("Authorization", "Bearer $token"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.latestAvailableVersion").isNotEmpty)
            .andExpect(jsonPath("$.tenants").isArray)
    }

    @Test
    fun `ordinary tenant admin is forbidden (fail-closed cross-tenant)`() {
        val token = login("admin@$slug.com")
        mockMvc.perform(
            get("/admin/tenants/migration-status").header("Authorization", "Bearer $token"),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `no token is unauthorized`() {
        mockMvc.perform(get("/admin/tenants/migration-status"))
            .andExpect(status().isUnauthorized)
    }
}
