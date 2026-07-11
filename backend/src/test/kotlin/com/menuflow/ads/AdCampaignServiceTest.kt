package com.menuflow.ads

import com.menuflow.IntegrationTestBase
import com.menuflow.exception.BusinessException
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.ifood.IfoodTokenCipher
import com.menuflow.model.AdAccount
import com.menuflow.model.AdCampaign
import com.menuflow.model.AdCampaignStatus
import com.menuflow.model.Product
import com.menuflow.model.control.SubscriptionPlan
import com.menuflow.model.control.Tenant
import com.menuflow.platform.TenantModule
import com.menuflow.platform.TenantModuleRepository
import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.tenant.AdAccountRepository
import com.menuflow.repository.tenant.AdCampaignRepository
import com.menuflow.repository.tenant.ProductRepository
import com.menuflow.security.AuthPrincipal
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.UUID

/**
 * Fase 8.2 (criar/pausar/ativar campanha) — nivel de service, MetaGraphClient MOCKADO
 * (nenhuma chamada real a Meta). Prova o NUCLEO monetario e a saga:
 *  (1) verba abaixo do piso -> 400; (2) verba acima do teto do tenant -> 400 (nao clampa);
 *  (3) sem teto configurado e sem env default -> 400 fail-closed; (4) idempotencia: 2 creates
 *  com a mesma chave = 1 campanha (2o devolve a existente); (5) falha parcial -> compensacao
 *  chama DELETE da campanha e nada fica persistido; (6) campanha nasce PAUSED; (7) ativar
 *  revalida o teto; (8) criar sem page_id -> 400; (10) isolamento de tenant.
 *
 * O gate de modulo/RBAC (@RequiresModule/@PreAuthorize) e HTTP e vive no controller test.
 */
class AdCampaignServiceTest @Autowired constructor(
    private val tenantRepository: TenantRepository,
    private val tenantModuleRepository: TenantModuleRepository,
    private val adAccountRepository: AdAccountRepository,
    private val campaignRepository: AdCampaignRepository,
    private val productRepository: ProductRepository,
    private val cipher: IfoodTokenCipher,
    private val service: AdCampaignService,
    private val adAccountService: AdAccountService,
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

    /** Cria tenant no controle e vincula principal+TenantContext; devolve o Tenant. */
    private fun bindTenant(plan: SubscriptionPlan = SubscriptionPlan.ENTERPRISE): Tenant {
        val slug = "adsc_${UUID.randomUUID().toString().take(8)}"
        val tenant = tenantRepository.save(Tenant(slug = slug, displayName = "Ads Camp", subscriptionPlan = plan))
        TenantContext.set(slug)
        val principal = AuthPrincipal(userId, slug, tenant.id!!, listOf("ADMIN"))
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, listOf(SimpleGrantedAuthority("ROLE_ADMIN")))
        return tenant
    }

    /** Define o teto de verba do tenant (cria/atualiza o override do modulo ADS no controle). */
    private fun setBudgetCap(tenantId: UUID, cents: Long) {
        val existing = tenantModuleRepository.findByTenantIdAndModuleKey(tenantId, "ADS")
        val row = existing?.apply { limitsJson = """{"max_daily_budget_cents": $cents}""" }
            ?: TenantModule(
                tenantId = tenantId,
                moduleKey = "ADS",
                enabled = true,
                updatedByUserId = userId,
                limitsJson = """{"max_daily_budget_cents": $cents}""",
            )
        tenantModuleRepository.save(row)
    }

    /** Conta CONNECTED com token cifrado; pageId setado por padrao (pre-requisito de campanha). */
    private fun seedAccount(withPage: Boolean = true): AdAccount {
        val (enc, iv) = cipher.encrypt(rawToken)
        return adAccountRepository.save(
            AdAccount(
                externalAccountId = "1234567890",
                accountName = "Conta",
                currency = "BRL",
                timezoneName = "America/Sao_Paulo",
                pageId = if (withPage) "999888777" else null,
                pageName = if (withPage) "Pagina Burger" else null,
                tokenEnc = enc,
                tokenIv = iv,
            ),
        )
    }

    /** Stub do caminho feliz da saga (4 escritas + effective_status). */
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

    private fun req(accountId: UUID, budgetCents: Long, productId: UUID? = null) = CreateAdCampaignRequest(
        accountId = accountId,
        name = "Promo Fim de Semana",
        dailyBudgetCents = budgetCents,
        geoLat = -0.034,
        geoLng = -51.07,
        radiusKm = 10,
        destinationUrl = "https://menu.exemplo.com/burger",
        primaryText = "O melhor burger de Macapa!",
        headline = "Peca agora",
        cta = "ORDER_NOW",
        productId = productId,
        trackingLinkId = null,
    )

    /** Produto no tenant corrente com a imageUrl dada (a foto vira imagem do anuncio). */
    private fun seedProduct(imageUrl: String?): Product =
        productRepository.save(
            Product(
                categoryId = UUID.randomUUID(),
                sku = "SKU-${UUID.randomUUID().toString().take(8)}",
                name = "Burger",
                priceCents = 2500,
                imageUrl = imageUrl,
            ),
        )

    @Test
    fun `verba abaixo do piso e rejeitada com 400`() {
        val t = bindTenant()
        setBudgetCap(t.id!!, 100_000)
        val acc = seedAccount()
        val ex = assertThrows(BusinessException::class.java) { service.create(req(acc.id!!, 500), "K1") }
        assert(ex.message!!.contains("minima"))
        assertEquals(0, campaignRepository.findAllByOrderByCreatedAtDesc().size, "nada pode persistir abaixo do piso")
    }

    @Test
    fun `verba acima do teto do tenant e rejeitada com 400 e nao clampa`() {
        val t = bindTenant()
        setBudgetCap(t.id!!, 5000) // R$50
        val acc = seedAccount()
        val ex = assertThrows(BusinessException::class.java) { service.create(req(acc.id!!, 6000), "K1") }
        assert(ex.message!!.contains("teto"))
        assertEquals(0, campaignRepository.findAllByOrderByCreatedAtDesc().size)
    }

    @Test
    fun `sem teto configurado e sem env default bloqueia (fail-closed)`() {
        bindTenant() // NENHUM override -> sem teto; ambiente de teste nao define o env default
        val acc = seedAccount()
        val ex = assertThrows(BusinessException::class.java) { service.create(req(acc.id!!, 2000), "K1") }
        assert(ex.message!!.contains("nao configurado") || ex.message!!.contains("Teto"))
        assertEquals(0, campaignRepository.findAllByOrderByCreatedAtDesc().size)
    }

    @Test
    fun `criar sem page_id na conta e rejeitado com 400`() {
        val t = bindTenant()
        setBudgetCap(t.id!!, 100_000)
        val acc = seedAccount(withPage = false)
        val ex = assertThrows(BusinessException::class.java) { service.create(req(acc.id!!, 5000), "K1") }
        assert(ex.message!!.contains("Pagina"))
    }

    @Test
    fun `campanha criada nasce PAUSED`() {
        val t = bindTenant()
        setBudgetCap(t.id!!, 100_000)
        val acc = seedAccount()
        stubHappySaga()
        val resp = service.create(req(acc.id!!, 5000), "K1")
        assertEquals(AdCampaignStatus.PAUSED, resp.status, "campanha deve nascer PAUSED")
        assertEquals("camp_ext_1", resp.externalCampaignId)
    }

    @Test
    fun `idempotencia dois creates com a mesma chave criam uma campanha`() {
        val t = bindTenant()
        setBudgetCap(t.id!!, 100_000)
        val acc = seedAccount()
        stubHappySaga()

        val first = service.create(req(acc.id!!, 5000), "SAME-KEY")
        val second = service.create(req(acc.id!!, 5000), "SAME-KEY")

        assertEquals(first.id, second.id, "retry com a mesma chave devolve a mesma campanha")
        assertEquals(1, campaignRepository.findAllByOrderByCreatedAtDesc().size, "uma unica campanha")
        // A saga externa so pode ter rodado UMA vez (nao recria/gasta no retry).
        Mockito.verify(meta, Mockito.times(1))
            .createCampaign(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString())
    }

    @Test
    fun `falha parcial na saga compensa apagando a campanha externa e nao deixa lixo`() {
        val t = bindTenant()
        setBudgetCap(t.id!!, 100_000)
        val acc = seedAccount()
        // Campanha externa criada, mas o adset falha -> deve compensar.
        Mockito.`when`(meta.createCampaign(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
            .thenReturn("camp_ext_1")
        Mockito.`when`(
            meta.createAdSet(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString(),
                Mockito.anyLong(), Mockito.anyDouble(), Mockito.anyDouble(), Mockito.anyInt(),
            ),
        ).thenThrow(MetaGraphException("adset recusado"))

        assertThrows(RuntimeException::class.java) { service.create(req(acc.id!!, 5000), "K1") }

        // Compensacao: DELETE da campanha externa foi chamado com o id certo. Verificamos com
        // VALORES CONCRETOS (o token que o service usa e o rawToken decifrado): eq()/matchers
        // em param String non-null do Kotlin dao NPE sem mockito-kotlin (licao conhecida).
        Mockito.verify(meta).deleteObject(rawToken, "camp_ext_1")
        // ...e nada ficou persistido localmente (reserva removida).
        assertEquals(0, campaignRepository.findAllByOrderByCreatedAtDesc().size, "compensacao nao pode deixar campanha orfa")
    }

    @Test
    fun `ativar revalida o teto e barra se a verba passou a exceder`() {
        val t = bindTenant()
        setBudgetCap(t.id!!, 100_000) // teto alto na criacao
        val acc = seedAccount()
        stubHappySaga()
        val created = service.create(req(acc.id!!, 8000), "K1") // R$80, dentro do teto
        assertEquals(AdCampaignStatus.PAUSED, created.status)

        // O teto cai abaixo da verba da campanha; ativar deve barrar (revalidacao).
        setBudgetCap(t.id!!, 5000)
        val ex = assertThrows(BusinessException::class.java) { service.activate(created.id) }
        assert(ex.message!!.contains("teto"))
        // Continua PAUSED (nao ativou na Meta): updateStatus(ACTIVE) nao pode ter sido chamado.
        // Verify com valores CONCRETOS (sem matchers) para nao cair no NPE de eq() non-null.
        assertEquals(AdCampaignStatus.PAUSED, service.get(created.id).status)
        Mockito.verify(meta, Mockito.never()).updateStatus(rawToken, "camp_ext_1", "ACTIVE")
    }

    @Test
    fun `isolamento de tenant campanha de um tenant nao aparece em outro`() {
        val a = bindTenant()
        setBudgetCap(a.id!!, 100_000)
        val acc = seedAccount()
        stubHappySaga()
        val created = service.create(req(acc.id!!, 5000), "K1")

        // Troca para OUTRO tenant (outro banco): a campanha simplesmente nao existe la.
        bindTenant()
        assertThrows(ResourceNotFoundException::class.java) { service.get(created.id) }
    }

    // --- Achado 1: ativar flipa campanha + adset + ad para ACTIVE -----------------------------

    @Test
    fun `ativar flipa campanha adset e ad para ACTIVE`() {
        val t = bindTenant()
        setBudgetCap(t.id!!, 100_000)
        val acc = seedAccount()
        stubHappySaga()
        val created = service.create(req(acc.id!!, 5000), "K1")
        assertEquals(AdCampaignStatus.PAUSED, created.status)

        val activated = service.activate(created.id)

        assertEquals(AdCampaignStatus.ACTIVE, activated.status, "campanha deve ficar ACTIVE")
        // Os TRES objetos precisam ir a ACTIVE (adset/ad nasceram PAUSED). Verify com valores
        // CONCRETOS (sem matchers) por causa do NPE de eq() em String non-null do Kotlin.
        Mockito.verify(meta).updateStatus(rawToken, "adset_ext_1", "ACTIVE")
        Mockito.verify(meta).updateStatus(rawToken, "ad_ext_1", "ACTIVE")
        Mockito.verify(meta).updateStatus(rawToken, "camp_ext_1", "ACTIVE")
    }

    // --- Achado 2: disconnect nao confia no status local (bloqueia por external id) -----------

    @Test
    fun `disconnect e bloqueado se ha campanha com external id mesmo com status local PAUSED`() {
        val t = bindTenant()
        setBudgetCap(t.id!!, 100_000)
        val acc = seedAccount()
        // Simula o DRIFT: a campanha existe na Meta (external_campaign_id != null) porem o status
        // LOCAL esta PAUSED (ex.: updateStatus(ACTIVE) sucedeu na Meta mas o commit local falhou).
        campaignRepository.save(
            AdCampaign(
                adAccountId = acc.id!!,
                name = "Drift",
                dailyBudgetCents = 5000,
                idempotencyKey = "DRIFT-1",
                status = AdCampaignStatus.PAUSED,
                externalCampaignId = "camp_ext_drift",
            ),
        )

        val ex = assertThrows(BusinessException::class.java) { adAccountService.disconnect(acc.id!!) }
        assert(ex.message!!.contains("Meta"))
        // A conta (com o token) NAO pode ter sido apagada — senao perderiamos o controle.
        assert(adAccountRepository.findById(acc.id!!).isPresent) { "conta nao pode ser desconectada com campanha na Meta" }
    }

    // --- Achado 3: SSRF via product.imageUrl --------------------------------------------------

    @Test
    fun `imageUrl com scheme http e rejeitada com 400 e nada e criado`() {
        val t = bindTenant()
        setBudgetCap(t.id!!, 100_000)
        val acc = seedAccount()
        val prod = seedProduct("http://8.8.8.8/burger.jpg") // http => inseguro
        stubHappySaga()

        val ex = assertThrows(BusinessException::class.java) { service.create(req(acc.id!!, 5000, prod.id), "K1") }
        assert(ex.message!!.contains("https"))
        // Falhou ANTES da saga: nenhuma escrita externa e nenhum download ocorreu.
        Mockito.verify(meta, Mockito.never()).createCampaign(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString())
        Mockito.verify(meta, Mockito.never()).uploadAdImage(Mockito.anyString(), Mockito.anyString(), Mockito.anyString())
        assertEquals(0, campaignRepository.findAllByOrderByCreatedAtDesc().size)
    }

    @Test
    fun `imageUrl apontando para IP privado interno e rejeitada com 400`() {
        val t = bindTenant()
        setBudgetCap(t.id!!, 100_000)
        val acc = seedAccount()
        // 169.254.169.254 = metadata da nuvem; 127.0.0.1/10.x = interno. Todas devem cair.
        listOf("https://169.254.169.254/latest/meta-data", "https://127.0.0.1/x", "https://10.0.0.5/x")
            .forEachIndexed { i, url ->
                val prod = seedProduct(url)
                val ex = assertThrows(BusinessException::class.java) { service.create(req(acc.id!!, 5000, prod.id), "PRIV-$i") }
                assert(ex.message!!.contains("interno") || ex.message!!.contains("privado"))
            }
        Mockito.verify(meta, Mockito.never()).uploadAdImage(Mockito.anyString(), Mockito.anyString(), Mockito.anyString())
        assertEquals(0, campaignRepository.findAllByOrderByCreatedAtDesc().size)
    }

    @Test
    fun `imageUrl https de host publico passa na validacao e a saga segue`() {
        val t = bindTenant()
        setBudgetCap(t.id!!, 100_000)
        val acc = seedAccount()
        // IP publico literal (nao faz DNS, deterministico offline) => passa no guard anti-SSRF.
        val prod = seedProduct("https://8.8.8.8/burger.jpg")
        stubHappySaga()

        val resp = service.create(req(acc.id!!, 5000, prod.id), "K1")

        assertEquals(AdCampaignStatus.PAUSED, resp.status)
        // Passou pela validacao => a saga chamou o upload da imagem com a URL publica.
        Mockito.verify(meta).uploadAdImage(rawToken, "1234567890", "https://8.8.8.8/burger.jpg")
    }
}
