package com.menuflow.ads

import com.menuflow.IntegrationTestBase
import com.menuflow.ifood.IfoodTokenCipher
import com.menuflow.model.AdAccount
import com.menuflow.model.control.SubscriptionPlan
import com.menuflow.model.control.Tenant
import com.menuflow.platform.TenantModule
import com.menuflow.platform.TenantModuleRepository
import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.tenant.AdAccountRepository
import com.menuflow.repository.tenant.AdCampaignRepository
import com.menuflow.security.AuthPrincipal
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * TESTE ADICIONAL (Testador/QA) — cobre a CORRIDA CONCORRENTE da idempotencia da Fase 8.2,
 * que os testes existentes NAO exercitam (o teste sequencial so bate o fast-path
 * findByAdAccountIdAndIdempotencyKey e nunca chega ao catch de DataIntegrityViolationException).
 *
 * Dois requests SIMULTANEOS com a MESMA Idempotency-Key na mesma conta: a UNIQUE
 * (ad_account_id, idempotency_key) da V60 deve deixar UM vencer e o outro receber a violacao,
 * que o service traduz devolvendo a campanha existente. Invariante monetaria: NUNCA duas
 * campanhas (dois gastos) e a saga externa (createCampaign) roda no maximo UMA vez.
 *
 * MetaGraphClient MOCKADO — nenhuma chamada real a Meta.
 */
class AdCampaignConcurrencyTest @Autowired constructor(
    private val tenantRepository: TenantRepository,
    private val tenantModuleRepository: TenantModuleRepository,
    private val adAccountRepository: AdAccountRepository,
    private val campaignRepository: AdCampaignRepository,
    private val cipher: IfoodTokenCipher,
    private val service: AdCampaignService,
) : IntegrationTestBase() {

    @MockitoBean
    private lateinit var meta: MetaGraphClient

    private val rawToken = "SYSUSR-EAAB-valid-token-abcdefghijklmnop-1234"
    private val userId = UUID.randomUUID()

    @AfterEach
    fun clear() {
        TenantContext.clear()
        SecurityContextHolder.clearContext()
    }

    private fun bindTenant(): Tenant {
        val slug = "adscc_${UUID.randomUUID().toString().take(8)}"
        val tenant = tenantRepository.save(Tenant(slug = slug, displayName = "Ads Race", subscriptionPlan = SubscriptionPlan.ENTERPRISE))
        TenantContext.set(slug)
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthPrincipal(userId, slug, tenant.id!!, listOf("ADMIN")),
                null,
                listOf(SimpleGrantedAuthority("ROLE_ADMIN")),
            )
        return tenant
    }

    private fun setBudgetCap(tenantId: UUID, cents: Long) {
        tenantModuleRepository.save(
            TenantModule(
                tenantId = tenantId,
                moduleKey = "ADS",
                enabled = true,
                updatedByUserId = userId,
                limitsJson = """{"max_daily_budget_cents": $cents}""",
            ),
        )
    }

    private fun seedAccount(): AdAccount {
        val (enc, iv) = cipher.encrypt(rawToken)
        return adAccountRepository.save(
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
    }

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
        Mockito.`when`(meta.fetchCampaignEffectiveStatus(Mockito.anyString(), Mockito.anyString()))
            .thenReturn("PAUSED")
    }

    private fun req(accountId: UUID) = CreateAdCampaignRequest(
        accountId = accountId,
        name = "Promo Corrida",
        dailyBudgetCents = 5000,
        geoLat = -0.034,
        geoLng = -51.07,
        radiusKm = 10,
        destinationUrl = "https://menu.exemplo.com/burger",
        primaryText = "O melhor burger de Macapa!",
        headline = "Peca agora",
        cta = "ORDER_NOW",
        productId = null,
        trackingLinkId = null,
    )

    @Test
    fun `dois creates concorrentes com a mesma chave criam uma unica campanha`() {
        val tenant = bindTenant()
        setBudgetCap(tenant.id!!, 100_000)
        val acc = seedAccount() // provisiona o banco do tenant ANTES das threads (evita corrida no CREATE DATABASE)
        stubHappySaga()

        val slug = tenant.slug
        val tenantId = tenant.id!!
        val accountId = acc.id!!
        val key = "RACE-KEY"

        val barrier = CyclicBarrier(2)
        val responses = CopyOnWriteArrayList<AdCampaignResponse>()
        val errors = CopyOnWriteArrayList<Throwable>()
        val pool = Executors.newFixedThreadPool(2)

        val worker = Runnable {
            try {
                TenantContext.set(slug)
                SecurityContextHolder.getContext().authentication =
                    UsernamePasswordAuthenticationToken(
                        AuthPrincipal(userId, slug, tenantId, listOf("ADMIN")),
                        null,
                        listOf(SimpleGrantedAuthority("ROLE_ADMIN")),
                    )
                barrier.await(10, TimeUnit.SECONDS) // dispara os dois ao mesmo tempo
                responses.add(service.create(req(accountId), key))
            } catch (t: Throwable) {
                errors.add(t)
            } finally {
                TenantContext.clear()
                SecurityContextHolder.clearContext()
            }
        }

        pool.submit(worker)
        pool.submit(worker)
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "as threads devem terminar")

        // Nenhuma das duas pode ter estourado: o perdedor recebe a campanha existente, nao um erro.
        assertTrue(errors.isEmpty(), "nenhum request pode falhar na corrida; erros=${errors.map { it.javaClass.simpleName + ':' + it.message }}")
        assertEquals(2, responses.size, "os dois requests devem responder")

        // Invariante forte: os dois enxergam a MESMA campanha (um unico id).
        assertEquals(1, responses.map { it.id }.toSet().size, "os dois requests devem devolver a MESMA campanha")

        // E existe exatamente UMA campanha persistida no banco do tenant.
        TenantContext.set(slug)
        val all = campaignRepository.findAllByOrderByCreatedAtDesc()
        assertEquals(1, all.size, "a corrida NAO pode criar duas campanhas (dois gastos)")

        // A saga externa (que GASTA) rodou no maximo UMA vez: o perdedor nunca chega a createCampaign.
        Mockito.verify(meta, Mockito.times(1))
            .createCampaign(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString())
    }
}
