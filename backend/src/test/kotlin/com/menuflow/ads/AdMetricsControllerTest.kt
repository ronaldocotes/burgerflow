package com.menuflow.ads

import com.fasterxml.jackson.databind.ObjectMapper
import com.menuflow.IntegrationTestBase
import com.menuflow.ifood.IfoodTokenCipher
import com.menuflow.model.AdAccount
import com.menuflow.model.AdMetricsSnapshot
import com.menuflow.model.control.SubscriptionPlan
import com.menuflow.model.control.Tenant
import com.menuflow.model.control.User
import com.menuflow.model.control.UserRole
import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.control.UserRepository
import com.menuflow.repository.tenant.AdAccountRepository
import com.menuflow.repository.tenant.AdMetricsSnapshotRepository
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.http.MediaType
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Fase 8.1 — slice HTTP do dashboard de metricas. Prova o gate e o escopo:
 *  - MANAGER le a serie de UMA conta do seu tenant (200 com a linha semeada);
 *  - STAFF recebe 403 (RBAC de classe, antes do service);
 *  - conta de OUTRO tenant nao e acessivel: 404 (db-per-tenant isola por banco).
 */
@AutoConfigureMockMvc
class AdMetricsControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val objectMapper: ObjectMapper,
    private val adAccountRepository: AdAccountRepository,
    private val snapshotRepository: AdMetricsSnapshotRepository,
    private val cipher: IfoodTokenCipher,
) : IntegrationTestBase() {

    @AfterEach
    fun clear() = TenantContext.clear()

    private fun seedTenant(
        plan: SubscriptionPlan = SubscriptionPlan.ENTERPRISE,
        role: UserRole = UserRole.MANAGER,
    ): String {
        val slug = "adsx_${UUID.randomUUID().toString().take(8)}"
        val tenant = tenantRepository.save(Tenant(slug = slug, displayName = "Ads X", subscriptionPlan = plan))
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

    /** Semeia uma conta + 1 snapshot no banco do tenant [slug]; devolve o id da conta. */
    private fun seedAccountWithSnapshot(slug: String, externalId: String): UUID {
        TenantContext.set(slug)
        val (enc, iv) = cipher.encrypt("token-$externalId")
        val acc = adAccountRepository.save(
            AdAccount(externalAccountId = externalId, accountName = "Conta", currency = "BRL", tokenEnc = enc, tokenIv = iv),
        )
        snapshotRepository.save(
            AdMetricsSnapshot(
                adAccountId = acc.id!!,
                snapshotDate = LocalDate.now(),
                spendCents = 4200,
                impressions = 1000,
                reach = 800,
                clicks = 40,
                ctrMilli = 4000,
                cpcCents = 105,
                isPartial = true,
                fetchedAt = Instant.now(),
            ),
        )
        TenantContext.clear()
        return acc.id!!
    }

    @Test
    fun `MANAGER le as metricas da conta do proprio tenant`() {
        val slug = seedTenant()
        val token = login(slug)
        val accountId = seedAccountWithSnapshot(slug, "1234567890")

        mockMvc.perform(get("/ads/accounts/$accountId/metrics?days=30").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].spendCents").value(4200))
            .andExpect(jsonPath("$[0].ctrMilli").value(4000))
            .andExpect(jsonPath("$[0].isPartial").value(true))
    }

    @Test
    fun `STAFF nao pode ler metricas (403)`() {
        val slug = seedTenant(role = UserRole.STAFF)
        val token = login(slug)
        val accountId = seedAccountWithSnapshot(slug, "9998887776")

        mockMvc.perform(get("/ads/accounts/$accountId/metrics").header("Authorization", "Bearer $token"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `conta de outro tenant nao e acessivel (404)`() {
        // Conta vive no tenant A.
        val slugA = seedTenant()
        val accountId = seedAccountWithSnapshot(slugA, "5554443332")

        // Usuario MANAGER do tenant B tenta ler as metricas da conta de A pelo id.
        val slugB = seedTenant()
        val tokenB = login(slugB)

        mockMvc.perform(get("/ads/accounts/$accountId/metrics").header("Authorization", "Bearer $tokenB"))
            .andExpect(status().isNotFound)
    }
}
