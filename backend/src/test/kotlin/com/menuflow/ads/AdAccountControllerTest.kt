package com.menuflow.ads

import com.fasterxml.jackson.databind.ObjectMapper
import com.menuflow.IntegrationTestBase
import com.menuflow.ifood.IfoodTokenCipher
import com.menuflow.model.control.SubscriptionPlan
import com.menuflow.model.control.Tenant
import com.menuflow.model.control.User
import com.menuflow.model.control.UserRole
import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.control.UserRepository
import com.menuflow.repository.tenant.AdAccountRepository
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
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
 * Fase 8.0 (Central de Trafego Pago, read-only) — slice HTTP de ponta a ponta:
 * login -> conectar/listar/desconectar conta Meta. O MetaGraphClient e MOCKADO
 * (nenhuma chamada real a Graph API). Verifica: token salvo CIFRADO e nunca devolvido,
 * token invalido nao persiste nada, RBAC (STAFF 403 / sem auth 401) e o gate de modulo
 * (tenant BASIC 403). Cada caso usa seu proprio tenant (db isolado).
 */
@AutoConfigureMockMvc
class AdAccountControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val objectMapper: ObjectMapper,
    private val adAccountRepository: AdAccountRepository,
    private val cipher: IfoodTokenCipher,
) : IntegrationTestBase() {

    @MockitoBean
    private lateinit var metaGraphClient: MetaGraphClient

    @AfterEach
    fun clear() = TenantContext.clear()

    private val rawToken = "SYSUSR-EAAB-valid-token-abcdefghijklmnop-1234"

    /** Cria um tenant (plano configuravel) + um usuario com o papel dado; devolve o slug. */
    private fun seedTenant(
        plan: SubscriptionPlan = SubscriptionPlan.ENTERPRISE,
        role: UserRole = UserRole.MANAGER,
    ): String {
        val slug = "ads_${UUID.randomUUID().toString().take(8)}"
        val tenant = tenantRepository.save(
            Tenant(slug = slug, displayName = "Ads Burger", subscriptionPlan = plan),
        )
        userRepository.save(
            User(
                tenantId = tenant.id!!,
                email = "user@$slug.com",
                passwordHash = passwordEncoder.encode("pass1234"),
                firstName = "User",
                role = role,
            ),
        )
        return slug
    }

    private fun login(slug: String): String {
        val body = objectMapper.writeValueAsString(
            mapOf("email" to "user@$slug.com", "password" to "pass1234", "tenantSlug" to slug),
        )
        val res = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isOk).andReturn()
        return objectMapper.readTree(res.response.contentAsString).get("token").asText()
    }

    private fun connectBody() = objectMapper.writeValueAsString(mapOf("token" to rawToken))

    @Test
    fun `conectar com token valido salva cifrado e nunca devolve o token`() {
        val slug = seedTenant()
        val token = login(slug)
        Mockito.`when`(metaGraphClient.fetchAdAccounts(Mockito.anyString())).thenReturn(
            listOf(MetaAdAccountDto("1234567890", "Minha Conta", "BRL", "America/Sao_Paulo")),
        )

        val res = mockMvc.perform(
            post("/ads/accounts").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(connectBody()),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$[0].accountName").value("Minha Conta"))
            .andExpect(jsonPath("$[0].accountIdLast4").value("7890"))
            .andExpect(jsonPath("$[0].currency").value("BRL"))
            .andExpect(jsonPath("$[0].status").value("CONNECTED"))
            // O token NUNCA aparece no DTO (nem em claro, nem cifrado).
            .andExpect(jsonPath("$[0].token").doesNotExist())
            .andExpect(jsonPath("$[0].tokenEnc").doesNotExist())
            .andReturn()

        assertFalse(res.response.contentAsString.contains(rawToken), "o token bruto nunca pode vazar na resposta")

        // No banco: token guardado CIFRADO e recuperavel via IfoodTokenCipher.
        TenantContext.set(slug)
        val rows = adAccountRepository.findAllByOrderByCreatedAtAsc()
        assertEquals(1, rows.size)
        val row = rows.first()
        assertTrue(row.tokenEnc.isNotEmpty() && row.tokenIv.isNotEmpty(), "token deve estar cifrado (enc+iv)")
        assertFalse(String(row.tokenEnc, Charsets.UTF_8).contains(rawToken), "o token nao pode estar em claro no banco")
        assertEquals(rawToken, cipher.decrypt(row.tokenEnc, row.tokenIv), "decifra deve devolver o token original")
    }

    @Test
    fun `reconectar a mesma conta atualiza em vez de duplicar`() {
        val slug = seedTenant()
        val token = login(slug)
        Mockito.`when`(metaGraphClient.fetchAdAccounts(Mockito.anyString())).thenReturn(
            listOf(MetaAdAccountDto("1234567890", "Nome Antigo", "BRL", "America/Sao_Paulo")),
        )
        mockMvc.perform(
            post("/ads/accounts").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(connectBody()),
        ).andExpect(status().isCreated)

        // Segunda conexao da MESMA conta externa, com nome novo.
        Mockito.`when`(metaGraphClient.fetchAdAccounts(Mockito.anyString())).thenReturn(
            listOf(MetaAdAccountDto("1234567890", "Nome Novo", "BRL", "America/Sao_Paulo")),
        )
        mockMvc.perform(
            post("/ads/accounts").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(connectBody()),
        ).andExpect(status().isCreated)

        TenantContext.set(slug)
        val rows = adAccountRepository.findAllByOrderByCreatedAtAsc()
        assertEquals(1, rows.size, "reconnect da mesma conta nao pode duplicar (UNIQUE + upsert)")
        assertEquals("Nome Novo", rows.first().accountName)
    }

    @Test
    fun `conectar com token invalido nao salva nada e retorna 400`() {
        val slug = seedTenant()
        val token = login(slug)
        Mockito.`when`(metaGraphClient.fetchAdAccounts(Mockito.anyString()))
            .thenThrow(MetaTokenInvalidException("Invalid OAuth access token"))

        mockMvc.perform(
            post("/ads/accounts").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(connectBody()),
        ).andExpect(status().isBadRequest)

        TenantContext.set(slug)
        assertEquals(0, adAccountRepository.findAllByOrderByCreatedAtAsc().size, "token invalido nao pode persistir conta")
    }

    @Test
    fun `STAFF nao pode conectar (403)`() {
        val slug = seedTenant(role = UserRole.STAFF)
        val token = login(slug)

        mockMvc.perform(
            post("/ads/accounts").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(connectBody()),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `sem autenticacao retorna 401`() {
        mockMvc.perform(
            post("/ads/accounts").contentType(MediaType.APPLICATION_JSON).content(connectBody()),
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `tenant sem o modulo habilitado (plano BASIC) recebe 403`() {
        val slug = seedTenant(plan = SubscriptionPlan.BASIC)
        val token = login(slug)
        // Mesmo com token que a Meta aceitaria, o gate de modulo barra antes do service.
        Mockito.`when`(metaGraphClient.fetchAdAccounts(Mockito.anyString())).thenReturn(
            listOf(MetaAdAccountDto("1234567890", "Conta", "BRL", "America/Sao_Paulo")),
        )

        mockMvc.perform(
            post("/ads/accounts").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(connectBody()),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `listar devolve contas sem token e desconectar remove`() {
        val slug = seedTenant()
        val token = login(slug)
        Mockito.`when`(metaGraphClient.fetchAdAccounts(Mockito.anyString())).thenReturn(
            listOf(MetaAdAccountDto("9998887776", "Conta X", "BRL", "America/Sao_Paulo")),
        )
        val created = mockMvc.perform(
            post("/ads/accounts").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(connectBody()),
        ).andExpect(status().isCreated).andReturn()
        val id = objectMapper.readTree(created.response.contentAsString)[0].get("id").asText()

        mockMvc.perform(get("/ads/accounts").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].accountIdLast4").value("7776"))
            .andExpect(jsonPath("$[0].token").doesNotExist())

        mockMvc.perform(delete("/ads/accounts/$id").header("Authorization", "Bearer $token"))
            .andExpect(status().isNoContent)

        mockMvc.perform(get("/ads/accounts").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))
    }

    // --- Paginas do Facebook (GET .../pages, PUT .../page) -----------------------------

    /** Conecta uma conta via API e devolve o id da conta recem-criada (pattern dos testes acima). */
    private fun connectAndGetId(token: String, externalId: String = "5551234321"): String {
        Mockito.`when`(metaGraphClient.fetchAdAccounts(Mockito.anyString())).thenReturn(
            listOf(MetaAdAccountDto(externalId, "Conta Pages", "BRL", "America/Sao_Paulo")),
        )
        val created = mockMvc.perform(
            post("/ads/accounts").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(connectBody()),
        ).andExpect(status().isCreated).andReturn()
        return objectMapper.readTree(created.response.contentAsString)[0].get("id").asText()
    }

    @Test
    fun `listar Paginas devolve as Paginas que o token administra (Meta mockada)`() {
        val slug = seedTenant()
        val token = login(slug)
        val id = connectAndGetId(token)
        Mockito.`when`(metaGraphClient.fetchPages(Mockito.anyString())).thenReturn(
            listOf(MetaPageDto("111", "Hamburgueria do Ze"), MetaPageDto("222", "Burger Prime")),
        )

        mockMvc.perform(get("/ads/accounts/$id/pages").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].id").value("111"))
            .andExpect(jsonPath("$[0].name").value("Hamburgueria do Ze"))
            .andExpect(jsonPath("$[1].id").value("222"))
            .andExpect(jsonPath("$[1].name").value("Burger Prime"))
    }

    @Test
    fun `STAFF nao pode listar Paginas (403)`() {
        val slug = seedTenant(role = UserRole.STAFF)
        val token = login(slug)
        // O gate de papel (classe @PreAuthorize) barra antes do service; id qualquer serve.
        mockMvc.perform(
            get("/ads/accounts/${UUID.randomUUID()}/pages").header("Authorization", "Bearer $token"),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `listar Paginas de conta de outro tenant retorna 404 (isolamento db-per-tenant)`() {
        val slugA = seedTenant()
        val tokenA = login(slugA)
        val idA = connectAndGetId(tokenA, externalId = "7000000001")

        // Outro tenant (db isolado) nao enxerga a conta do tenant A -> 404, nao 200/403.
        val slugB = seedTenant()
        val tokenB = login(slugB)
        mockMvc.perform(get("/ads/accounts/$idA/pages").header("Authorization", "Bearer $tokenB"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `gravar Pagina persiste e o GET de contas reflete pageId e pageName`() {
        val slug = seedTenant()
        val token = login(slug)
        val id = connectAndGetId(token, externalId = "8000000002")

        val putBody = objectMapper.writeValueAsString(
            mapOf("pageId" to "999888", "pageName" to "Minha Pagina"),
        )
        mockMvc.perform(
            put("/ads/accounts/$id/page").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(putBody),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pageId").value("999888"))
            .andExpect(jsonPath("$.pageName").value("Minha Pagina"))
            // O token JAMAIS aparece, nem apos gravar a Pagina.
            .andExpect(jsonPath("$.token").doesNotExist())

        // Persistiu no banco do tenant.
        TenantContext.set(slug)
        val row = adAccountRepository.findAllByOrderByCreatedAtAsc().first()
        assertEquals("999888", row.pageId)
        assertEquals("Minha Pagina", row.pageName)

        // E a listagem (via novo campo do DTO da Tarefa 1) reflete a Pagina ja escolhida.
        mockMvc.perform(get("/ads/accounts").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].pageId").value("999888"))
            .andExpect(jsonPath("$[0].pageName").value("Minha Pagina"))
    }

    @Test
    fun `STAFF nao pode gravar Pagina (403)`() {
        val slug = seedTenant(role = UserRole.STAFF)
        val token = login(slug)
        val putBody = objectMapper.writeValueAsString(mapOf("pageId" to "999", "pageName" to "X"))
        mockMvc.perform(
            put("/ads/accounts/${UUID.randomUUID()}/page").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(putBody),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `gravar Pagina com pageId vazio retorna 400 (bean validation)`() {
        val slug = seedTenant()
        val token = login(slug)
        val id = connectAndGetId(token, externalId = "8000000003")
        // pageId em branco viola @NotBlank -> 400 antes de tocar o service.
        val putBody = objectMapper.writeValueAsString(mapOf("pageId" to "", "pageName" to "X"))
        mockMvc.perform(
            put("/ads/accounts/$id/page").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(putBody),
        ).andExpect(status().isBadRequest)
    }
}
