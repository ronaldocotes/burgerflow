package com.menuflow.platform

import com.fasterxml.jackson.databind.ObjectMapper
import com.menuflow.IntegrationTestBase
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
 * Contrato de segurança da Fase F2 do painel super-admin:
 *  - GET /admin/integrations/health  → só SUPER_ADMIN (401/403 para outros)
 *  - GET /admin/tenants/{slug}/usage → só SUPER_ADMIN (401/403 para outros)
 *  - GET /admin/ifood-apps           → só SUPER_ADMIN (401/403 para outros)
 *
 * Padrão de e-mail dinâmico (role@slug.com) para evitar colisão no banco
 * compartilhado da suite: mesmo e-mail pode existir em tenants distintos por
 * design, mas usar slug único por teste é mais robusto (sem dep. da UNIQUE key).
 *
 * As respostas de conteúdo dependem de integrações externas indisponíveis no
 * teste; verificamos apenas o gate de autorização. O health endpoint retorna 200
 * com cards DOWN quando os serviços externos não estão acessíveis — correto
 * (fail-open por card).
 */
@AutoConfigureMockMvc
class PlatformF2SecurityTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val objectMapper: ObjectMapper,
) : IntegrationTestBase() {

    private lateinit var slug: String

    @BeforeEach
    fun seed() {
        slug = "f2s${UUID.randomUUID().toString().replace("-", "").take(9)}"
        tenantRepository.save(Tenant(slug = slug, displayName = "F2 Sec Burger"))
    }

    private fun addUser(role: UserRole): String {
        val email = "${role.name.lowercase()}@$slug.com"
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
        return email
    }

    private fun login(email: String): String {
        val body = objectMapper.writeValueAsString(
            mapOf("email" to email, "password" to "pass1234", "tenantSlug" to slug),
        )
        val res = mockMvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isOk).andReturn()
        return objectMapper.readTree(res.response.contentAsString).get("token").asText()
    }

    // ── /admin/integrations/health ───────────────────────────────────────────

    @Test
    fun `integrations health - sem token recebe 401`() {
        mockMvc.perform(get("/admin/integrations/health"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `integrations health - ADMIN comum recebe 403`() {
        val token = login(addUser(UserRole.ADMIN))
        mockMvc.perform(
            get("/admin/integrations/health").header("Authorization", "Bearer $token"),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `integrations health - SUPER_ADMIN recebe 200 com 4 cards`() {
        val token = login(addUser(UserRole.SUPER_ADMIN))
        mockMvc.perform(
            get("/admin/integrations/health").header("Authorization", "Bearer $token"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.updatedAt").exists())
            .andExpect(jsonPath("$.cards").isArray)
            .andExpect(jsonPath("$.cards.length()").value(4))
    }

    // ── /admin/tenants/{slug}/usage ──────────────────────────────────────────

    @Test
    fun `usage - sem token recebe 401`() {
        mockMvc.perform(get("/admin/tenants/$slug/usage"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `usage - ADMIN comum recebe 403`() {
        val token = login(addUser(UserRole.ADMIN))
        mockMvc.perform(
            get("/admin/tenants/$slug/usage").header("Authorization", "Bearer $token"),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `usage - SUPER_ADMIN recebe 200 com metricas`() {
        val token = login(addUser(UserRole.SUPER_ADMIN))
        mockMvc.perform(
            get("/admin/tenants/$slug/usage").header("Authorization", "Bearer $token"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.ordersThisMonth").exists())
            .andExpect(jsonPath("$.dbSizeMb").exists())
    }

    // ── /admin/ifood-apps ────────────────────────────────────────────────────

    @Test
    fun `ifood-apps - sem token recebe 401`() {
        mockMvc.perform(get("/admin/ifood-apps"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `ifood-apps - ADMIN comum recebe 403`() {
        val token = login(addUser(UserRole.ADMIN))
        mockMvc.perform(
            get("/admin/ifood-apps").header("Authorization", "Bearer $token"),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `ifood-apps - SUPER_ADMIN lista (vazio) com 200`() {
        val token = login(addUser(UserRole.SUPER_ADMIN))
        mockMvc.perform(
            get("/admin/ifood-apps").header("Authorization", "Bearer $token"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
    }
}
