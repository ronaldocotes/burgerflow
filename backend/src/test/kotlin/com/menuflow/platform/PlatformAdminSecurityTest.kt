package com.menuflow.platform

import com.fasterxml.jackson.databind.ObjectMapper
import com.menuflow.IntegrationTestBase
import com.menuflow.model.control.Tenant
import com.menuflow.model.control.User
import com.menuflow.model.control.UserRole
import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.control.UserRepository
import org.junit.jupiter.api.Assertions.assertTrue
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
 * Contrato de segurança do painel super-admin (Fase 1). O gate do prefixo admin é DUPLO
 * (path-level em SecurityConfig + @PreAuthorize nos controllers); estes testes
 * provam o comportamento observável:
 *
 *  - ADMIN comum em /admin/tenants  => 403 (autenticado, sem o papel SUPER_ADMIN)
 *  - sem token em /admin/tenants    => 401 (não autenticado)
 *  - SUPER_ADMIN em /admin/tenants  => 200 (happy path)
 *  - SUPER_ADMIN provisiona tenant  => 201 + inviteLink (token cru só aqui)
 */
@AutoConfigureMockMvc
class PlatformAdminSecurityTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val objectMapper: ObjectMapper,
) : IntegrationTestBase() {

    private lateinit var slug: String

    @BeforeEach
    fun seed() {
        slug = "plat${UUID.randomUUID().toString().take(8)}"
        tenantRepository.save(Tenant(slug = slug, displayName = "Platform Burger"))
    }

    private fun addUser(email: String, role: UserRole) {
        val tenant = tenantRepository.findBySlug(slug)!!
        userRepository.save(
            User(
                tenantId = tenant.id!!,
                email = email,
                passwordHash = passwordEncoder.encode("pass1234"),
                firstName = "U",
                role = role,
            ),
        )
    }

    private fun login(email: String): String {
        val body = objectMapper.writeValueAsString(
            mapOf("email" to email, "password" to "pass1234", "tenantSlug" to slug),
        )
        val res = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isOk).andReturn()
        return objectMapper.readTree(res.response.contentAsString).get("token").asText()
    }

    @Test
    fun `ADMIN comum recebe 403 em admin tenants`() {
        addUser("admin@$slug.com", UserRole.ADMIN)
        val token = login("admin@$slug.com")
        mockMvc.perform(get("/admin/tenants").header("Authorization", "Bearer $token"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `sem token recebe 401 em admin tenants`() {
        mockMvc.perform(get("/admin/tenants"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `SUPER_ADMIN lista tenants com 200`() {
        addUser("root@$slug.com", UserRole.SUPER_ADMIN)
        val token = login("root@$slug.com")
        mockMvc.perform(get("/admin/tenants").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
    }

    @Test
    fun `SUPER_ADMIN provisiona tenant e recebe inviteLink`() {
        addUser("root@$slug.com", UserRole.SUPER_ADMIN)
        val token = login("root@$slug.com")

        val newSlug = "burg${UUID.randomUUID().toString().take(8)}"
        val body = objectMapper.writeValueAsString(
            mapOf(
                "slug" to newSlug,
                "displayName" to "Novo Restaurante",
                "adminEmail" to "dono@$newSlug.com",
                "plan" to "PRO",
            ),
        )
        val res = mockMvc.perform(
            post("/admin/tenants")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.slug").value(newSlug))
            .andReturn()

        val inviteLink = objectMapper.readTree(res.response.contentAsString).get("inviteLink").asText()
        assertTrue(inviteLink.contains("token="))
        // O tenant nasceu no banco de controle.
        assertTrue(tenantRepository.existsBySlug(newSlug))
    }

    @Test
    fun `slug invalido no provisionamento recebe 400`() {
        addUser("root@$slug.com", UserRole.SUPER_ADMIN)
        val token = login("root@$slug.com")
        val body = objectMapper.writeValueAsString(
            mapOf(
                "slug" to "AB",
                "displayName" to "X",
                "adminEmail" to "a@b.com",
            ),
        )
        mockMvc.perform(
            post("/admin/tenants")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isBadRequest)
    }
}
