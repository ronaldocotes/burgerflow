package com.menuflow

import com.menuflow.dto.CampaignCreateRequest
import com.menuflow.model.Campaign
import com.menuflow.model.CampaignSegment
import com.menuflow.model.CampaignStatus
import com.menuflow.model.Customer
import com.menuflow.model.Order
import com.menuflow.model.RfvSegment
import com.menuflow.model.SendStatus
import com.menuflow.repository.tenant.CampaignRepository
import com.menuflow.repository.tenant.CampaignSendRepository
import com.menuflow.repository.tenant.CustomerRepository
import com.menuflow.service.CampaignDispatcher
import com.menuflow.service.CampaignService
import com.menuflow.service.RfvService
import com.menuflow.service.WhatsAppService
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * RFV + Campanhas WhatsApp (Fase 3.4) contra Postgres real (Testcontainers). Cada
 * caso usa seu PROPRIO tenant (db isolado) e NAO e @Transactional: os saves comitam,
 * para o dispatcher (transacoes proprias via TransactionTemplate) enxergar os dados.
 * O WhatsAppService e mockado: nenhum disparo real ao WAHA.
 */
class CampaignServiceTest @Autowired constructor(
    private val campaignService: CampaignService,
    private val campaignDispatcher: CampaignDispatcher,
    private val rfvService: RfvService,
    private val customerRepository: CustomerRepository,
    private val campaignRepository: CampaignRepository,
    private val campaignSendRepository: CampaignSendRepository,
) : IntegrationTestBase() {

    @MockitoBean
    private lateinit var whatsAppService: WhatsAppService

    private lateinit var tenant: String

    private fun <T> anyArg(): T = Mockito.any()

    @BeforeEach
    fun setup() {
        tenant = "camp_${UUID.randomUUID().toString().take(8)}"
        TenantContext.set(tenant)
        // Por padrao o envio "funciona"; o delay e zerado nos testes (sem espera real).
        Mockito.`when`(whatsAppService.sendCampaign(anyArg(), anyArg(), Mockito.any())).thenReturn(true)
    }

    @AfterEach
    fun clear() = TenantContext.clear()

    @Autowired
    private lateinit var orderRepository: com.menuflow.repository.tenant.OrderRepository

    @Autowired
    private lateinit var tenantConfigRepository: com.menuflow.repository.tenant.TenantConfigRepository

    private fun newCustomer(name: String, optIn: Boolean): Customer {
        TenantContext.set(tenant)
        val phone = "9${UUID.randomUUID().toString().filter { it.isDigit() }.take(9)}"
        return customerRepository.save(
            Customer(name = name, phoneNumber = phone, loyaltyPoints = 0, marketingOptIn = optIn),
        )
    }

    /** Persiste um pedido com createdAt no passado (createdAt e val do construtor). */
    private fun persistOrder(customerId: UUID, totalCents: Long, daysAgo: Long) {
        TenantContext.set(tenant)
        orderRepository.save(
            Order(
                orderNumber = "MF-T-${UUID.randomUUID().toString().take(8)}",
                customerId = customerId,
                totalCents = totalCents,
                createdAt = Instant.now().minus(daysAgo, ChronoUnit.DAYS),
            ),
        )
    }

    // ---- RFV: classificacao pura (sem I/O) ----

    @Test
    fun `RFV classifica LOYAL, AT_RISK, INACTIVE e NEW`() {
        // lifetime<=1 -> NEW
        assertEquals(RfvSegment.NEW, RfvService.classify(recencyDays = 2, freq90 = 1, lifetimeOrders = 1))
        // recencia>45 -> INACTIVE
        assertEquals(RfvSegment.INACTIVE, RfvService.classify(recencyDays = 60, freq90 = 0, lifetimeOrders = 3))
        // recencia<14 e freq>=3 -> LOYAL
        assertEquals(RfvSegment.LOYAL, RfvService.classify(recencyDays = 5, freq90 = 4, lifetimeOrders = 8))
        // tem historia, ativo, nao fiel -> AT_RISK
        assertEquals(RfvSegment.AT_RISK, RfvService.classify(recencyDays = 20, freq90 = 2, lifetimeOrders = 4))
    }

    @Test
    fun `RFV scoreAll calcula recencia, frequencia e ticket medio`() {
        val c = newCustomer("Fiel", optIn = true)
        // 3 pedidos recentes (R$30, R$50, R$40) -> freq90=3, recencia pequena, ticket=4000
        persistOrder(c.id!!, 3000, 2)
        persistOrder(c.id!!, 5000, 4)
        persistOrder(c.id!!, 4000, 6)

        TenantContext.set(tenant)
        val score = rfvService.scoreAll().first { it.customerId == c.id }
        assertEquals(3, score.frequency)
        assertEquals(4000, score.monetaryValue) // (3000+5000+4000)/3
        assertTrue(score.recencyDays <= 3)
        assertEquals(RfvSegment.LOYAL, score.segment)
    }

    // ---- buildRecipients ----

    @Test
    fun `buildRecipients ignora clientes sem opt-in`() {
        newCustomer("ComOptIn-A", optIn = true)
        newCustomer("ComOptIn-B", optIn = true)
        newCustomer("SemOptIn", optIn = false)

        TenantContext.set(tenant)
        val recipients = campaignService.buildRecipients(CampaignSegment.ALL_OPT_IN, dailyLimit = 100)
        assertEquals(2, recipients.size, "so os 2 com opt-in entram")
        assertTrue(recipients.all { it.customer.marketingOptIn })
    }

    @Test
    fun `buildRecipients respeita o limite diario`() {
        repeat(3) { newCustomer("Cliente$it", optIn = true) }

        TenantContext.set(tenant)
        val recipients = campaignService.buildRecipients(CampaignSegment.ALL_OPT_IN, dailyLimit = 1)
        assertEquals(1, recipients.size)
    }

    // ---- interpolate ----

    @Test
    fun `interpolate substitui nome, pontos e dias e adiciona emoji`() {
        TenantContext.set(tenant)
        val customer = Customer(name = "Ana", phoneNumber = "91999990000", loyaltyPoints = 30)
        val score = com.menuflow.model.RfvScore(
            customerId = UUID.randomUUID(), customerName = "Ana", phoneNumber = null,
            recencyDays = 5, frequency = 2, monetaryValue = 4000, segment = RfvSegment.AT_RISK,
        )
        val msg = campaignService.interpolate("Oi {nome}, voce tem {pontos} pontos e sumiu ha {dias} dias", customer, score)

        assertTrue(msg.contains("Oi Ana,"))
        assertTrue(msg.contains("30 pontos"))
        assertTrue(msg.contains("5 dias"))
        assertTrue(CampaignService.EMOJIS.any { msg.startsWith(it) }, "deve comecar com um emoji do tema")
    }

    // ---- opt-out ----

    @Test
    fun `optOut marca marketingOptIn false e cancela envios em fila`() {
        val c = newCustomer("Desistente", optIn = true)
        // Cria uma campanha que enfileira o cliente.
        TenantContext.set(tenant)
        campaignService.create(
            CampaignCreateRequest(name = "Promo", messageTemplate = "Oi {nome}", segment = CampaignSegment.ALL_OPT_IN),
        )

        TenantContext.set(tenant)
        campaignService.optOutByPhone(c.phoneNumber)

        TenantContext.set(tenant)
        assertFalse(customerRepository.findById(c.id!!).get().marketingOptIn)
        val sends = campaignSendRepository.findByCustomerIdAndStatus(c.id!!, SendStatus.OPT_OUT)
        assertEquals(1, sends.size, "o envio em fila virou OPT_OUT")
    }

    // ---- dispatch ----

    @Test
    fun `campanha com 0 destinatarios fica COMPLETED imediatamente`() {
        // Nenhum cliente com opt-in -> 0 destinatarios.
        TenantContext.set(tenant)
        val created = campaignService.create(
            CampaignCreateRequest(name = "Vazia", messageTemplate = "Oi", segment = CampaignSegment.ALL_OPT_IN),
        )
        assertEquals(0, created.totalRecipients)

        TenantContext.set(tenant)
        campaignDispatcher.runDispatch(created.id)

        TenantContext.set(tenant)
        assertEquals(CampaignStatus.COMPLETED, campaignRepository.findById(created.id).get().status)
    }

    @Test
    fun `dispatch envia para todos os destinatarios e conta os envios`() {
        zeroDelay()
        newCustomer("A", optIn = true)
        newCustomer("B", optIn = true)

        TenantContext.set(tenant)
        val created = campaignService.create(
            CampaignCreateRequest(name = "Promo", messageTemplate = "Oi {nome}", segment = CampaignSegment.ALL_OPT_IN),
        )
        assertEquals(2, created.totalRecipients)

        TenantContext.set(tenant)
        campaignDispatcher.runDispatch(created.id)

        TenantContext.set(tenant)
        val campaign = campaignRepository.findById(created.id).get()
        assertEquals(CampaignStatus.COMPLETED, campaign.status)
        assertEquals(2, campaign.sentCount)
        assertEquals(0, campaign.failedCount)
        val sent = campaignSendRepository.countByCampaignIdAndStatus(created.id, SendStatus.SENT)
        assertEquals(2, sent)
        // O WAHA foi chamado uma vez por destinatario.
        Mockito.verify(whatsAppService, Mockito.times(2)).sendCampaign(anyArg(), anyArg(), Mockito.any())
    }

    // ---- agendamento (CampaignSchedulerJob -> startDueScheduled) ----

    @Test
    fun `create com scheduledAt nasce SCHEDULED`() {
        TenantContext.set(tenant)
        val created = campaignService.create(
            CampaignCreateRequest(
                name = "Agendada", messageTemplate = "Oi", segment = CampaignSegment.ALL_OPT_IN,
                scheduledAt = Instant.now().plus(1, ChronoUnit.HOURS),
            ),
        )
        assertEquals(CampaignStatus.SCHEDULED, created.status)
    }

    @Test
    fun `campanha agendada no passado dispara UMA vez e nao redispara`() {
        zeroDelay()
        newCustomer("Agendado", optIn = true)
        TenantContext.set(tenant)
        val created = campaignService.create(
            CampaignCreateRequest(
                name = "Agendada", messageTemplate = "Oi {nome}", segment = CampaignSegment.ALL_OPT_IN,
                scheduledAt = Instant.now().minus(5, ChronoUnit.MINUTES),
            ),
        )
        assertEquals(CampaignStatus.SCHEDULED, created.status)

        TenantContext.set(tenant)
        assertEquals(1, campaignService.startDueScheduled(tenant), "primeiro tick reivindica e dispara")
        // Segundo tick: a transicao atomica ja aconteceu -> nada a disparar.
        TenantContext.set(tenant)
        assertEquals(0, campaignService.startDueScheduled(tenant), "segundo tick NAO redispara")

        // O despacho e assincrono (mesmo caminho do start manual); aguarda concluir.
        awaitStatus(created.id, CampaignStatus.COMPLETED)
        TenantContext.set(tenant)
        val campaign = campaignRepository.findById(created.id).get()
        assertEquals(1, campaign.sentCount)
        // O WAHA (mock) recebeu exatamente UM envio: sem duplicidade.
        Mockito.verify(whatsAppService, Mockito.times(1)).sendCampaign(anyArg(), anyArg(), Mockito.any())
    }

    @Test
    fun `campanha PAUSED com horario vencido nao dispara pelo agendador`() {
        TenantContext.set(tenant)
        val created = campaignService.create(
            CampaignCreateRequest(
                name = "Pausada", messageTemplate = "Oi", segment = CampaignSegment.ALL_OPT_IN,
                scheduledAt = Instant.now().minus(5, ChronoUnit.MINUTES),
            ),
        )
        TenantContext.set(tenant)
        campaignRepository.findById(created.id).get().let {
            it.status = CampaignStatus.PAUSED
            campaignRepository.save(it)
        }

        TenantContext.set(tenant)
        assertEquals(0, campaignService.startDueScheduled(tenant))
        TenantContext.set(tenant)
        assertEquals(CampaignStatus.PAUSED, campaignRepository.findById(created.id).get().status)
    }

    @Test
    fun `campanha agendada no futuro ainda nao dispara`() {
        TenantContext.set(tenant)
        val created = campaignService.create(
            CampaignCreateRequest(
                name = "Futura", messageTemplate = "Oi", segment = CampaignSegment.ALL_OPT_IN,
                scheduledAt = Instant.now().plus(1, ChronoUnit.HOURS),
            ),
        )
        TenantContext.set(tenant)
        assertEquals(0, campaignService.startDueScheduled(tenant))
        TenantContext.set(tenant)
        assertEquals(CampaignStatus.SCHEDULED, campaignRepository.findById(created.id).get().status)
    }

    /** Aguarda (ate 10s) o status esperado — o dispatch roda em thread @Async. */
    private fun awaitStatus(id: UUID, expected: CampaignStatus) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            TenantContext.set(tenant)
            if (campaignRepository.findById(id).get().status == expected) return
            Thread.sleep(100)
        }
        TenantContext.set(tenant)
        assertEquals(expected, campaignRepository.findById(id).get().status, "timeout aguardando status")
    }

    /** Zera o delay anti-ban no tenant_config para o teste nao dormir de verdade. */
    private fun zeroDelay() {
        TenantContext.set(tenant)
        val config = tenantConfigRepository.findFirstByOrderByCreatedAtAsc() ?: com.menuflow.model.TenantConfig()
        config.campaignDelayMinSeconds = 0
        config.campaignDelayMaxSeconds = 0
        tenantConfigRepository.save(config)
    }
}
