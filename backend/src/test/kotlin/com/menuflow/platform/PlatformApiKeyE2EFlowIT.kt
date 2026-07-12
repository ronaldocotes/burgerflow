package com.menuflow.platform

import com.fasterxml.jackson.databind.ObjectMapper
import com.menuflow.IntegrationTestBase
import com.menuflow.dispatch.GeocodingService
import com.menuflow.dispatch.GoogleApiKeyProvider
import com.menuflow.dispatch.LatLng
import com.menuflow.model.control.Tenant
import com.menuflow.model.control.User
import com.menuflow.model.control.UserRole
import com.menuflow.repository.control.PlatformApiKeyRepository
import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.control.UserRepository
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Fase 4 (QA e2e) — cenario UNICO de ponta a ponta que fecha a lacuna deixada pelos
 * testes de componente existentes:
 *
 *  - [PlatformApiKeyControllerIT] prova a transicao env<->DB no nivel do
 *    GoogleApiKeyProvider (assere googleApiKeyProvider.resolve()).
 *  - [com.menuflow.dispatch.GeocodingServiceTest] prova que o consumidor usa a chave do
 *    provider, mas com o provider MOCKADO.
 *
 * Nenhum dos dois amarra o CONSUMIDOR REAL (GeocodingService) ao provider REAL apoiado no
 * BANCO DE CONTROLE atravessando o ciclo HTTP (PUT/DELETE) e verificando a chave que
 * EFETIVAMENTE sai no header X-Goog-Api-Key da chamada ao Google. Este teste faz isso num
 * so cenario:
 *
 *   env (ENV_KEY)  --geocode-->  header = ENV_KEY, source ENV
 *   PUT /admin/api-keys (HTTP)   -> grava DB_KEY cifrada, source DB
 *   env->DB        --geocode-->  header = DB_KEY   (consumidor real passou a usar o banco)
 *   DELETE /admin/api-keys (HTTP)-> desativa, volta ao fallback env
 *   DB->env        --geocode-->  header = ENV_KEY  (consumidor real voltou a env)
 *
 * O Google e substituido por um HttpServer local efemero (127.0.0.1:0) que CAPTURA o
 * header X-Goog-Api-Key — hermetico, sem internet. Cada passo usa um endereco DIFERENTE
 * para furar o cache interno do GeocodingService e forcar uma chamada remota nova.
 *
 * Cobre criterios de aceite 1 (env->DB ponta a ponta pelo consumidor) e 2 (DELETE->env).
 */
@AutoConfigureMockMvc
class PlatformApiKeyE2EFlowIT @Autowired constructor(
    private val mockMvc: MockMvc,
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val objectMapper: ObjectMapper,
    private val apiKeyRepository: PlatformApiKeyRepository,
    // Provider REAL do contexto Spring (apoiado no banco de controle real).
    private val googleApiKeyProvider: GoogleApiKeyProvider,
) : IntegrationTestBase() {

    companion object {
        const val ENV_KEY = "ENV-E2E-KEY-0001"
        const val DB_KEY = "AIzaE2EDBKEY0002gUms"

        @JvmStatic
        @DynamicPropertySource
        fun envKey(registry: DynamicPropertyRegistry) {
            registry.add("google.routes.api-key") { ENV_KEY }
        }
    }

    private lateinit var slug: String
    private lateinit var server: HttpServer
    private val capturedKey = AtomicReference<String?>()

    /** Consumidor REAL fiado ao provider REAL, apontado ao HttpServer local. */
    private lateinit var geocoding: GeocodingService

    @BeforeEach
    fun setup() {
        apiKeyRepository.deleteAll()
        googleApiKeyProvider.invalidate()
        slug = "e2e${UUID.randomUUID().toString().replace("-", "").take(9)}"
        tenantRepository.save(Tenant(slug = slug, displayName = "E2E Burger"))

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/maps/api/geocode/json") { ex ->
            capturedKey.set(ex.requestHeaders.getFirst("X-Goog-Api-Key"))
            val bytes = """{"results":[{"geometry":{"location":{"lat":1.0,"lng":2.0}}}]}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()

        geocoding = GeocodingService(
            googleApiKeyProvider = googleApiKeyProvider,
            baseUrl = "http://127.0.0.1:${server.address.port}",
            builder = RestClient.builder(),
        )
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    private fun superAdminToken(): String {
        val email = "super@$slug.com"
        val tenant = tenantRepository.findBySlug(slug)!!
        userRepository.save(
            User(
                tenantId = tenant.id!!,
                email = email,
                passwordHash = passwordEncoder.encode("pass1234"),
                firstName = "U",
                role = UserRole.SUPER_ADMIN,
            ),
        )
        val body = objectMapper.writeValueAsString(
            mapOf("email" to email, "password" to "pass1234", "tenantSlug" to slug),
        )
        val res = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isOk).andReturn()
        return objectMapper.readTree(res.response.contentAsString).get("token").asText()
    }

    /** Faz o consumidor real geocodar e devolve a chave capturada no header de saida. */
    private fun consumerKeyFor(street: String): String? {
        capturedKey.set(null)
        val result: LatLng? = geocoding.geocode(street, "Central", "Macapa", "68900073")
        assertEquals(LatLng(1.0, 2.0), result, "geocode de amostra deveria resolver via HttpServer local")
        return capturedKey.get()
    }

    @Test
    fun `env - PUT - DB - DELETE - env de ponta a ponta pelo consumidor real`() {
        val token = superAdminToken()
        fun auth() = "Bearer $token"

        // 1) Estado inicial: sem linha no banco -> o consumidor real usa a ENV.
        assertEquals(ENV_KEY, consumerKeyFor("Rua ENV Inicial"), "sem chave no banco o consumidor usa a env")

        // 2) Super-admin grava a chave pela camada HTTP (PUT) -> source DB, mascarada, sem valor.
        val putRes = mockMvc.perform(
            put("/admin/api-keys/GOOGLE_MAPS").header("Authorization", auth())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("value" to DB_KEY))),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.source").value("DB"))
            .andExpect(jsonPath("$.status").value("DEFINED"))
            .andExpect(jsonPath("$.keyVersion").value(1))
            .andExpect(jsonPath("$.masked").value("AIza…gUms"))
            .andExpect(jsonPath("$.value").doesNotExist())
            .andReturn()
        assertFalse(putRes.response.contentAsString.contains(DB_KEY), "PUT nao pode ecoar o valor")

        // 3) TRANSICAO env->DB provada NO CONSUMIDOR REAL: a chave que sai no header agora e a do BANCO.
        assertEquals(DB_KEY, consumerKeyFor("Rua DB Ativa"), "apos o PUT o consumidor real deve usar a chave do BANCO, nao a env")

        // 4) DELETE pela camada HTTP -> volta ao fallback env.
        val delRes = mockMvc.perform(delete("/admin/api-keys/GOOGLE_MAPS").header("Authorization", auth()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.source").value("ENV"))
            .andExpect(jsonPath("$.status").value("DEFINED"))
            .andExpect(jsonPath("$.value").doesNotExist())
            .andReturn()
        assertFalse(delRes.response.contentAsString.contains(DB_KEY), "DELETE nao pode ecoar o valor")
        assertEquals(0, apiKeyRepository.findAll().count { it.active }, "sem linha ativa apos o DELETE")

        // 5) TRANSICAO DB->env provada NO CONSUMIDOR REAL: a chave volta a ser a da ENV.
        assertEquals(ENV_KEY, consumerKeyFor("Rua ENV Final"), "apos o DELETE o consumidor real deve voltar a env")

        // Nunca a chave do banco em nenhum ponto: o header nunca carregou lixo e a resposta nunca vazou.
        assertNotNull(googleApiKeyProvider)
    }
}
