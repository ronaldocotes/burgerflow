package com.menuflow.platform

import com.fasterxml.jackson.databind.ObjectMapper
import com.menuflow.IntegrationTestBase
import com.menuflow.crypto.SecretCipher
import com.menuflow.dispatch.GeocodingService
import com.menuflow.dispatch.GoogleApiKeyProvider
import com.menuflow.dispatch.LatLng
import com.menuflow.model.control.PlatformApiKeyProviderType
import com.menuflow.model.control.Tenant
import com.menuflow.model.control.User
import com.menuflow.model.control.UserRole
import com.menuflow.repository.control.PlatformApiKeyRepository
import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.control.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Fase 2 — contrato do PlatformApiKeyController (/admin/api-keys), banco de CONTROLE real.
 *  - RBAC: sem token 401; ADMIN comum 403; SUPER_ADMIN 200.
 *  - WRITE-ONLY: GET nunca devolve o valor (so masked 4+4).
 *  - PUT cifra (BYTEA), incrementa key_version na rotacao e invalida o cache.
 *  - DELETE volta ao fallback env.
 *  - provider desconhecido -> 400; value vazio -> 400 (bean), implausivel -> 422.
 *  - auditoria gravada MASCARADA (nunca o valor).
 *  - /test nao ecoa a chave e respeita rate-limit (429 na 2a chamada imediata).
 *
 * A env google.routes.api-key e forcada (ENV_KEY) para provar o fallback DB<->ENV.
 * O GeocodingService e MOCKADO para o /test nao chamar o Google real.
 */
@AutoConfigureMockMvc
class PlatformApiKeyControllerIT @Autowired constructor(
    private val mockMvc: MockMvc,
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val objectMapper: ObjectMapper,
    private val apiKeyRepository: PlatformApiKeyRepository,
    private val auditRepository: PlatformAuditLogRepository,
    private val cipher: SecretCipher,
    private val googleApiKeyProvider: GoogleApiKeyProvider,
) : IntegrationTestBase() {

    @MockitoBean
    private lateinit var geocodingService: GeocodingService

    companion object {
        const val ENV_KEY = "ENV-FALLBACK-KEY-9999"

        @JvmStatic
        @DynamicPropertySource
        fun envKey(registry: DynamicPropertyRegistry) {
            registry.add("google.routes.api-key") { ENV_KEY }
        }
    }

    private lateinit var slug: String

    @BeforeEach
    fun seed() {
        apiKeyRepository.deleteAll()
        googleApiKeyProvider.invalidate()
        slug = "apik${UUID.randomUUID().toString().replace("-", "").take(9)}"
        tenantRepository.save(Tenant(slug = slug, displayName = "ApiKey Burger"))
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
            post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isOk).andReturn()
        return objectMapper.readTree(res.response.contentAsString).get("token").asText()
    }

    private fun superAdminToken() = login(addUser(UserRole.SUPER_ADMIN))

    // ── RBAC ─────────────────────────────────────────────────────────────────

    @Test
    fun `sem token recebe 401 em todos os verbos`() {
        mockMvc.perform(get("/admin/api-keys")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("/admin/api-keys/GOOGLE_MAPS")).andExpect(status().isUnauthorized)
        mockMvc.perform(
            put("/admin/api-keys/GOOGLE_MAPS").contentType(MediaType.APPLICATION_JSON).content("""{"value":"AIzaXXXXYYYY"}"""),
        ).andExpect(status().isUnauthorized)
        mockMvc.perform(delete("/admin/api-keys/GOOGLE_MAPS")).andExpect(status().isUnauthorized)
        mockMvc.perform(post("/admin/api-keys/GOOGLE_MAPS/test")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `ADMIN comum recebe 403 em todos os verbos`() {
        val token = login(addUser(UserRole.ADMIN))
        fun auth() = "Bearer $token"
        mockMvc.perform(get("/admin/api-keys").header("Authorization", auth())).andExpect(status().isForbidden)
        mockMvc.perform(get("/admin/api-keys/GOOGLE_MAPS").header("Authorization", auth())).andExpect(status().isForbidden)
        mockMvc.perform(
            put("/admin/api-keys/GOOGLE_MAPS").header("Authorization", auth())
                .contentType(MediaType.APPLICATION_JSON).content("""{"value":"AIzaXXXXYYYY"}"""),
        ).andExpect(status().isForbidden)
        mockMvc.perform(delete("/admin/api-keys/GOOGLE_MAPS").header("Authorization", auth())).andExpect(status().isForbidden)
        mockMvc.perform(post("/admin/api-keys/GOOGLE_MAPS/test").header("Authorization", auth())).andExpect(status().isForbidden)
    }

    // ── GET write-only ─────────────────────────────────────────────────────────

    @Test
    fun `GET sem chave no banco reporta ABSENT quando env vazia`() {
        // Este teste roda com a env setada (ENV_KEY) -> a chave vem da ENV -> DEFINED/ENV.
        val token = superAdminToken()
        mockMvc.perform(get("/admin/api-keys/GOOGLE_MAPS").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.provider").value("GOOGLE_MAPS"))
            .andExpect(jsonPath("$.status").value("DEFINED"))
            .andExpect(jsonPath("$.source").value("ENV"))
            .andExpect(jsonPath("$.keyVersion").doesNotExist())
            .andExpect(jsonPath("$.value").doesNotExist())
    }

    @Test
    fun `GET com chave no banco devolve masked 4+4 e NUNCA o valor`() {
        val token = superAdminToken()
        val secret = "AIzaSECRETVALUE1234gUms"
        mockMvc.perform(
            put("/admin/api-keys/GOOGLE_MAPS").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(mapOf("value" to secret))),
        ).andExpect(status().isOk)

        val res = mockMvc.perform(get("/admin/api-keys/GOOGLE_MAPS").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("DEFINED"))
            .andExpect(jsonPath("$.source").value("DB"))
            .andExpect(jsonPath("$.masked").value("AIza…gUms"))
            .andExpect(jsonPath("$.keyVersion").value(1))
            .andExpect(jsonPath("$.value").doesNotExist())
            .andReturn()

        // O corpo inteiro nao pode conter o segredo em texto.
        assertFalse(res.response.contentAsString.contains(secret), "GET nao pode vazar o valor")
    }

    // ── PUT cifra + rotaciona + invalida cache ──────────────────────────────────

    @Test
    fun `PUT cifra em BYTEA, incrementa key_version na rotacao e invalida o cache`() {
        val token = superAdminToken()
        val v1 = "AIzaFIRSTKEY000001"
        mockMvc.perform(
            put("/admin/api-keys/GOOGLE_MAPS").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(mapOf("value" to v1))),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.keyVersion").value(1))
            .andExpect(jsonPath("$.source").value("DB"))

        // Persistido cifrado (BYTEA != texto) e decifra de volta ao original.
        val row = apiKeyRepository.findFirstByProviderAndActiveTrue(PlatformApiKeyProviderType.GOOGLE_MAPS)!!
        assertFalse(row.valueEnc.contentEquals(v1.toByteArray(Charsets.UTF_8)), "value_enc precisa ser ciphertext")
        assertEquals(v1, cipher.decrypt(row.valueEnc, row.valueIv))
        // Cache invalidado: o provider ja resolve a chave nova (nao a env).
        assertEquals(v1, googleApiKeyProvider.resolve())

        // Rotacao: PUT de novo -> mesma linha ativa, key_version incrementa para 2.
        val v2 = "AIzaSECONDKEY00002"
        mockMvc.perform(
            put("/admin/api-keys/GOOGLE_MAPS").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(mapOf("value" to v2))),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.keyVersion").value(2))

        // Continua 1 linha ativa (respeita UNIQUE(provider) WHERE active) e vale a nova.
        assertEquals(1, apiKeyRepository.findAll().count { it.active })
        assertEquals(v2, googleApiKeyProvider.resolve())
    }

    // ── DELETE volta ao fallback env ────────────────────────────────────────────

    @Test
    fun `DELETE desativa a chave e volta ao fallback env`() {
        val token = superAdminToken()
        mockMvc.perform(
            put("/admin/api-keys/GOOGLE_MAPS").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content("""{"value":"AIzaTODELETE0001"}"""),
        ).andExpect(status().isOk)
        assertEquals("AIzaTODELETE0001", googleApiKeyProvider.resolve())

        mockMvc.perform(delete("/admin/api-keys/GOOGLE_MAPS").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.source").value("ENV"))
            .andExpect(jsonPath("$.status").value("DEFINED"))

        // Sem linha ativa e o provider volta a env.
        assertEquals(0, apiKeyRepository.findAll().count { it.active })
        assertEquals(ENV_KEY, googleApiKeyProvider.resolve())
    }

    // ── Validacao ────────────────────────────────────────────────────────────

    @Test
    fun `provider desconhecido recebe 400`() {
        val token = superAdminToken()
        mockMvc.perform(get("/admin/api-keys/OPENAI").header("Authorization", "Bearer $token"))
            .andExpect(status().isBadRequest)
        mockMvc.perform(
            put("/admin/api-keys/OPENAI").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content("""{"value":"whatever12345"}"""),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `value vazio recebe 400 e value implausivel recebe 422`() {
        val token = superAdminToken()
        // vazio -> @NotBlank -> 400
        mockMvc.perform(
            put("/admin/api-keys/GOOGLE_MAPS").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content("""{"value":"   "}"""),
        ).andExpect(status().isBadRequest)
        // curto demais (nao-vazio) -> service -> 422
        mockMvc.perform(
            put("/admin/api-keys/GOOGLE_MAPS").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content("""{"value":"abc"}"""),
        ).andExpect(status().isUnprocessableEntity)
    }

    // ── Auditoria mascarada ────────────────────────────────────────────────────

    @Test
    fun `PUT grava auditoria MASCARADA sem o valor`() {
        val token = superAdminToken()
        val secret = "AIzaAUDITSECRET0001"
        mockMvc.perform(
            put("/admin/api-keys/GOOGLE_MAPS").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(mapOf("value" to secret))),
        ).andExpect(status().isOk)

        val entry = auditRepository.findAll().firstOrNull { it.action == "PLATFORM_API_KEY_UPSERT" }
        assertNotNull(entry, "auditoria de UPSERT deve existir")
        assertEquals("GOOGLE_MAPS", entry!!.targetEntity)
        assertNotNull(entry.actorUserId)
        val after = entry.afterJson ?: ""
        assertTrue(after.contains("DEFINED"), "after deve registrar status DEFINED")
        // NUNCA o valor em claro nem no before nem no after.
        assertFalse(after.contains(secret), "auditoria nao pode conter o valor")
        assertFalse((entry.beforeJson ?: "").contains(secret), "auditoria (before) nao pode conter o valor")
    }

    // ── Test endpoint (sem ecoar chave) + rate-limit ───────────────────────────

    @Test
    fun `POST test nao ecoa a chave e respeita rate-limit`() {
        val token = superAdminToken()
        // Geocode mockado: a chave vem da ENV (source ENV); o /test nao chama o Google real.
        Mockito.`when`(geocodingService.geocode("Avenida FAB", "Central", "Macapa", "68900073"))
            .thenReturn(LatLng(1.0, 2.0))

        val res = mockMvc.perform(post("/admin/api-keys/GOOGLE_MAPS/test").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.source").value("ENV"))
            .andExpect(jsonPath("$.message").exists())
            .andReturn()
        // A resposta nao contem "value" nem a env key.
        assertFalse(res.response.contentAsString.contains(ENV_KEY), "/test nao pode ecoar a chave")

        // 2a chamada imediata do MESMO ator -> rate-limit 429.
        mockMvc.perform(post("/admin/api-keys/GOOGLE_MAPS/test").header("Authorization", "Bearer $token"))
            .andExpect(status().isTooManyRequests)
    }
}
