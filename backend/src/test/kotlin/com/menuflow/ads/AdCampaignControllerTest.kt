package com.menuflow.ads

import com.fasterxml.jackson.databind.ObjectMapper
import com.menuflow.IntegrationTestBase
import com.menuflow.ifood.IfoodTokenCipher
import com.menuflow.model.AdAccount
import com.menuflow.model.control.SubscriptionPlan
import com.menuflow.model.control.Tenant
import com.menuflow.model.control.User
import com.menuflow.model.control.UserRole
import com.menuflow.platform.TenantModule
import com.menuflow.platform.TenantModuleRepository
import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.control.UserRepository
import com.menuflow.repository.tenant.AdAccountRepository
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Fase 8.2 — slice HTTP: RBAC (STAFF 403), Idempotency-Key obrigatorio (400 sem ele) e o
 * caminho feliz (201, campanha nasce PAUSED) com o MetaGraphClient MOCKADO. O NUCLEO
 * monetario/saga/idempotencia esta em [AdCampaignServiceTest]; aqui provamos a borda HTTP.
 */
@AutoConfigureMockMvc
class AdCampaignControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val tenantRepository: TenantRepository,
    private val tenantModuleRepository: TenantModuleRepository,
    private val userRepository: UserRepository,
    private val adAccountRepository: AdAccountRepository,
    private val passwordEncoder: PasswordEncoder,
    private val objectMapper: ObjectMapper,
    private val cipher: IfoodTokenCipher,
) : IntegrationTestBase() {

    @MockitoBean
    private lateinit var meta: MetaGraphClient

    private val rawToken = "SYSUSR-EAAB-valid-token-abcdefghijklmnop-1234"

    @AfterEach
    fun clear() = TenantContext.clear()

    private fun seedTenant(role: UserRole = UserRole.MANAGER): Tenant {
        val slug = "adscc_${UUID.randomUUID().toString().take(8)}"
        val tenant = tenantRepository.save(Tenant(slug = slug, displayName = "Ads Camp", subscriptionPlan = SubscriptionPlan.ENTERPRISE))
        userRepository.save(
            User(
                tenantId = tenant.id!!,
                email = "user@$slug.com",
                passwordHash = passwordEncoder.encode("pass1234"),
                firstName = "User",
                role = role,
            ),
        )
        // Teto de verba (tambem habilita o modulo ADS via override).
        tenantModuleRepository.save(
            TenantModule(
                tenantId = tenant.id!!,
                moduleKey = "ADS",
                enabled = true,
                updatedByUserId = tenant.id!!,
                limitsJson = """{"max_daily_budget_cents": 100000}""",
            ),
        )
        return tenant
    }

    private fun seedAccount(slug: String): UUID {
        TenantContext.set(slug)
        val (enc, iv) = cipher.encrypt(rawToken)
        val acc = adAccountRepository.save(
            AdAccount(
                externalAccountId = "1234567890",
                accountName = "Conta",
                currency = "BRL",
                timezoneName = "America/Sao_Paulo",
                pageId = "999888777",
                pageName = "Pagina",
                tokenEnc = enc,
                tokenIv = iv,
            ),
        )
        TenantContext.clear()
        return acc.id!!
    }

    private fun login(slug: String): String {
        val body = objectMapper.writeValueAsString(
            mapOf("email" to "user@$slug.com", "password" to "pass1234", "tenantSlug" to slug),
        )
        val res = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk).andReturn()
        return objectMapper.readTree(res.response.contentAsString).get("token").asText()
    }

    private fun createBody(accountId: UUID) = objectMapper.writeValueAsString(
        mapOf(
            "accountId" to accountId,
            "name" to "Promo",
            "dailyBudgetCents" to 5000,
            "geoLat" to -0.034,
            "geoLng" to -51.07,
            "radiusKm" to 10,
            "destinationUrl" to "https://menu.exemplo.com",
            "primaryText" to "O melhor burger!",
        ),
    )

    private fun stubHappySaga() {
        Mockito.`when`(meta.createCampaign(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
            .thenReturn("camp_ext_1")
        Mockito.`when`(
            meta.createAdSet(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString(),
                Mockito.anyLong(), Mockito.anyDouble(), Mockito.anyDouble(), Mockito.anyInt(),
            ),
        ).thenReturn("adset_ext_1")
        Mockito.`when`(
            meta.createAdCreative(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString(),
                Mockito.anyString(), Mockito.anyString(), Mockito.any(),
            ),
        ).thenReturn("creative_ext_1")
        Mockito.`when`(meta.createAd(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
            .thenReturn("ad_ext_1")
        Mockito.`when`(meta.fetchCampaignEffectiveStatus(Mockito.anyString(), Mockito.anyString())).thenReturn("PAUSED")
    }

    @Test
    fun `STAFF nao pode criar campanha (403)`() {
        val tenant = seedTenant(role = UserRole.STAFF)
        val token = login(tenant.slug)
        val accountId = seedAccount(tenant.slug)

        mockMvc.perform(
            post("/ads/campaigns").header("Authorization", "Bearer $token")
                .header("Idempotency-Key", "K1")
                .contentType(MediaType.APPLICATION_JSON).content(createBody(accountId)),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `sem Idempotency-Key retorna 400`() {
        val tenant = seedTenant()
        val token = login(tenant.slug)
        val accountId = seedAccount(tenant.slug)

        mockMvc.perform(
            post("/ads/campaigns").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(createBody(accountId)),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `ADMIN cria campanha e ela nasce PAUSED (201)`() {
        val tenant = seedTenant(role = UserRole.ADMIN)
        val token = login(tenant.slug)
        val accountId = seedAccount(tenant.slug)
        stubHappySaga()

        mockMvc.perform(
            post("/ads/campaigns").header("Authorization", "Bearer $token")
                .header("Idempotency-Key", "K1")
                .contentType(MediaType.APPLICATION_JSON).content(createBody(accountId)),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("PAUSED"))
            .andExpect(jsonPath("$.externalCampaignId").value("camp_ext_1"))
    }
}
