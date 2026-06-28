package com.menuflow

import com.fasterxml.jackson.databind.ObjectMapper
import com.menuflow.model.control.Tenant
import com.menuflow.model.control.User
import com.menuflow.model.control.UserRole
import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.control.UserRepository
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Fase 2.2 — módulo de usuários/convites (banco de CONTROLE) via HTTP.
 *
 *  1. Fluxo de convite: ADMIN convida -> aceite público (token+nome+senha) ->
 *     login com a nova conta funciona.
 *  2. Anti-lockout: rebaixar o ÚNICO admin ativo do tenant -> 409.
 */
@AutoConfigureMockMvc
class UserManagementTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val objectMapper: ObjectMapper,
) : IntegrationTestBase() {

    private lateinit var slug: String
    private lateinit var adminId: UUID

    @BeforeEach
    fun seed() {
        slug = "users_${UUID.randomUUID().toString().take(8)}"
        val tenant = tenantRepository.save(Tenant(slug = slug, displayName = "Users Burger"))
        val admin = userRepository.save(
            User(
                tenantId = tenant.id!!,
                email = "admin@$slug.com",
                passwordHash = passwordEncoder.encode("pass1234"),
                firstName = "Admin",
                role = UserRole.ADMIN,
            ),
        )
        adminId = admin.id!!
    }

    private fun login(email: String, password: String): String {
        val body = objectMapper.writeValueAsString(
            mapOf("email" to email, "password" to password, "tenantSlug" to slug),
        )
        val res = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isOk).andReturn()
        return objectMapper.readTree(res.response.contentAsString).get("token").asText()
    }

    @Test
    fun `invite then accept then login with the new account`() {
        val adminToken = login("admin@$slug.com", "pass1234")

        // ADMIN convida um STAFF.
        val inviteBody = objectMapper.writeValueAsString(
            mapOf("email" to "novo@$slug.com", "role" to "STAFF"),
        )
        val inviteRes = mockMvc.perform(
            post("/users/invite")
                .header("Authorization", "Bearer $adminToken")
                .contentType(MediaType.APPLICATION_JSON).content(inviteBody),
        ).andExpect(status().isCreated).andReturn()
        val inviteLink = objectMapper.readTree(inviteRes.response.contentAsString).get("inviteLink").asText()
        val rawToken = inviteLink.substringAfter("token=")
        assertTrue(rawToken.isNotBlank())

        // Aceite PÚBLICO (sem auth): token + nome + senha.
        val acceptBody = objectMapper.writeValueAsString(
            mapOf(
                "token" to rawToken,
                "firstName" to "Novo",
                "lastName" to "Funcionario",
                "password" to "senha123",
            ),
        )
        val acceptRes = mockMvc.perform(
            post("/auth/accept-invite").contentType(MediaType.APPLICATION_JSON).content(acceptBody),
        ).andExpect(status().isOk).andReturn()
        assertNotNull(objectMapper.readTree(acceptRes.response.contentAsString).get("token").asText())

        // A nova conta loga normalmente.
        val newToken = login("novo@$slug.com", "senha123")
        assertTrue(newToken.isNotBlank())

        // E aparece na listagem de usuários (admin + novo).
        mockMvc.perform(get("/users").header("Authorization", "Bearer $adminToken"))
            .andExpect(status().isOk)
    }

    @Test
    fun `demoting the only active admin is rejected with 409`() {
        val adminToken = login("admin@$slug.com", "pass1234")
        val body = objectMapper.writeValueAsString(mapOf("role" to "STAFF"))
        mockMvc.perform(
            patch("/users/$adminId/role")
                .header("Authorization", "Bearer $adminToken")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isConflict)
    }
}
