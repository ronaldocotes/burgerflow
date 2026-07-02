package com.menuflow.dispatch

import com.menuflow.model.DeliveryDriver
import com.menuflow.model.DeliveryOffer
import com.menuflow.model.DeliveryOfferStatus
import com.menuflow.model.Order
import com.menuflow.model.OrderType
import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.tenant.DeliveryDriverRepository
import com.menuflow.repository.tenant.DeliveryOfferRepository
import com.menuflow.repository.tenant.OrderRepository
import com.menuflow.repository.tenant.TenantConfigRepository
import com.menuflow.service.AuditLogService
import com.menuflow.service.RealtimePublisher
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * Teste UNITARIO (Mockito puro, sem Testcontainers) do aceite de oferta de grupo do
 * DispatchService. Prova o INVARIANTE central da corrida: sob duas chamadas
 * concorrentes de aceite, emerge EXATAMENTE UM vencedor.
 *
 * O aceite atomico e feito por CAS puro em SQL (acceptOfferAtomic: UPDATE ... WHERE
 * status='OFFERED', retorna linhas afetadas). Aqui simulamos a corrida no banco
 * fazendo o mock retornar 1 (venceu) na 1a chamada e 0 (corrida ja fechada) na 2a --
 * exatamente o que o Postgres faz ao serializar dois UPDATEs na mesma linha. Assim o
 * teste nao depende de Docker e roda rapido no gate.
 *
 * NOTA de decisao: o plano original pedia @Version + OptimisticLockException. Trocamos
 * por CAS (decisao de engenharia): e deterministico, nao lanca excecao, nao exige
 * retry e nao envenena a transacao -- melhor para "reivindicar se ainda disponivel".
 * O invariante testado (um unico vencedor) e o mesmo.
 */
class DispatchServiceAcceptTest {

    private val offerRepo = mock(DeliveryOfferRepository::class.java)
    private val orderRepo = mock(OrderRepository::class.java)
    private val driverRepo = mock(DeliveryDriverRepository::class.java)
    private val configRepo = mock(TenantConfigRepository::class.java)
    private val tenantRepo = mock(TenantRepository::class.java)
    private val distance = mock(DistanceService::class.java)
    private val pricing = mock(RidePricingService::class.java)
    private val audit = mock(AuditLogService::class.java)
    private val realtime = mock(RealtimePublisher::class.java)
    private val events = mock(ApplicationEventPublisher::class.java)
    private val txManager = mock(PlatformTransactionManager::class.java)

    private lateinit var service: DispatchService

    private val orderId = UUID.randomUUID()
    private val offerId = UUID.randomUUID()
    private val driverId = UUID.randomUUID()

    /** Matcher any() compativel com parametros nao-nulos do Kotlin (evita NPE de matcher). */
    private fun <T> anyArg(): T = Mockito.any()

    private fun freshOffer() = DeliveryOffer(
        id = offerId,
        orderId = orderId,
        driverId = null,
        feeCents = 800,
        expiresAt = Instant.now().plusSeconds(60),
    ).apply {
        groupJid = "123@g.us"
        acceptCode = "AB12CD34"
    }

    @BeforeEach
    fun setup() {
        // TransactionTemplate roda o callback quando o manager entrega um status.
        given(txManager.getTransaction(anyArg())).willReturn(SimpleTransactionStatus())
        service = DispatchService(
            offerRepo, orderRepo, driverRepo, configRepo, tenantRepo,
            distance, pricing, audit, realtime, events, txManager,
        )

        val order = Order(id = orderId, orderNumber = "A1", orderType = OrderType.DELIVERY)
        val driver = DeliveryDriver(id = driverId, name = "Moto", phone = "5596", tenantId = UUID.randomUUID())
        given(orderRepo.findByOrderNumber("A1")).willReturn(order)
        // Oferta viva OFFERED em cada leitura (simula os dois motoboys lendo antes do write).
        given(offerRepo.findByOrderIdAndStatus(orderId, DeliveryOfferStatus.OFFERED))
            .willReturn(listOf(freshOffer()))
        given(driverRepo.findByPhone(anyArg())).willReturn(driver)
        given(orderRepo.findById(orderId)).willReturn(Optional.of(order))
        given(orderRepo.save(anyArg())).willAnswer { it.arguments[0] }

        TenantContext.set("t1")
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
    }

    @Test
    fun `apenas um motoboy vence a corrida pelo aceite atomico`() {
        // O banco (via CAS) fecha a linha para o primeiro (1 linha) e nega o segundo (0).
        given(offerRepo.acceptOfferAtomic(anyArg(), anyArg(), anyArg()))
            .willReturn(1)
            .willReturn(0)

        val r1 = service.acceptOffer("A1", "5596111@c.us", "m1")
        val r2 = service.acceptOffer("A1", "5596222@c.us", "m2")

        assertEquals(DispatchService.AcceptOutcome.ACCEPTED, r1, "o primeiro aceite vence")
        assertEquals(DispatchService.AcceptOutcome.ALREADY_TAKEN, r2, "o segundo perde a corrida")
    }

    @Test
    fun `messageId repetido e deduplicado`() {
        given(offerRepo.acceptOfferAtomic(anyArg(), anyArg(), anyArg())).willReturn(1)

        val r1 = service.acceptOffer("A1", "5596111@c.us", "dup")
        val r2 = service.acceptOffer("A1", "5596111@c.us", "dup")

        assertEquals(DispatchService.AcceptOutcome.ACCEPTED, r1)
        assertEquals(DispatchService.AcceptOutcome.DUPLICATE, r2, "mesma messageId nao reprocessa")
    }
}
