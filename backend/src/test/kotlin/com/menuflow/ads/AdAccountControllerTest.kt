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
}
