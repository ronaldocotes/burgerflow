package com.menuflow

import com.menuflow.client.AsaasClient
import com.menuflow.client.AsaasCustomerRequest
import com.menuflow.client.AsaasCustomerResponse
import com.menuflow.client.AsaasPaymentRequest
import com.menuflow.client.AsaasPaymentResponse
import com.menuflow.client.AsaasPixQrResponse
import com.menuflow.dto.AsaasWebhookBody
import com.menuflow.dto.OrderCreateRequest
import com.menuflow.dto.OrderItemRequest
import com.menuflow.dto.ProductCreateRequest
import com.menuflow.model.PaymentIntentStatus
import com.menuflow.model.PaymentStatus
import com.menuflow.service.OrderService
import com.menuflow.service.PixPaymentService
import com.menuflow.service.ProductService
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.UUID

/**
 * Pagamento PIX via Asaas (Fase 2.3) contra um Postgres real (Testcontainers). O
 * AsaasClient e MOCKADO — nenhuma chamada HTTP sai. Cada caso usa seu proprio tenant
 * (db isolado). O teste NAO e @Transactional: cada chamada de servico commita, como
 * no CashSessionTest, de modo que a 2a chamada enxerga o estado da 1a.
 *
 * Cobre: (1) criacao idempotente (2a chamada devolve a MESMA cobranca, sem novo POST
 * ao Asaas); (2) webhook marca pedido como PAID; (3) reentrega do mesmo evento e
 * no-op (deduplicacao por event_id).
 */
class PixPaymentServiceTest @Autowired constructor(
    private val pixPaymentService: PixPaymentService,
    private val orderService: OrderService,
    private val productService: ProductService,
    private val paymentIntentRepository: com.menuflow.repository.tenant.PaymentIntentRepository,
    private val orderRepository: com.menuflow.repository.tenant.OrderRepository,
) : IntegrationTestBase() {

    @MockitoBean
    private lateinit var asaasClient: AsaasClient

    @AfterEach
    fun clear() = TenantContext.clear()

    /** Matcher any() compativel com parametros nao-nulos do Kotlin. */
    private fun <T> anyArg(): T = Mockito.any()

    private fun stubAsaas(asaasPaymentId: String) {
        Mockito.`when`(asaasClient.createCustomer(anyArg<AsaasCustomerRequest>()))
            .thenReturn(AsaasCustomerResponse(id = "cus_test"))
        Mockito.`when`(asaasClient.createPayment(anyArg<AsaasPaymentRequest>()))
            .thenReturn(AsaasPaymentResponse(id = asaasPaymentId, status = "PENDING", value = 25.0, externalReference = null))
        Mockito.`when`(asaasClient.getPixQr(Mockito.anyString()))
            .thenReturn(AsaasPixQrResponse(encodedImage = "img64", payload = "copia-e-cola", expirationDate = null))
    }

    private fun seedOrder(tenant: String): UUID {
        TenantContext.set(tenant)
        val productId = productService.create(
            ProductCreateRequest(
                categoryId = UUID.randomUUID(),
                sku = "P-${UUID.randomUUID().toString().take(6)}",
                name = "Burger",
                priceCents = 2_500,
            ),
        ).id
        TenantContext.set(tenant)
        return orderService.create(
            OrderCreateRequest(items = listOf(OrderItemRequest(productId = productId, quantity = 1))),
            userId = null,
        ).id
    }

    @Test
    fun `createPixCharge e idempotente - segunda chamada devolve a mesma cobranca sem novo POST`() {
        val tenant = "pix_${UUID.randomUUID().toString().take(8)}"
        stubAsaas("pay_idem")
        val orderId = seedOrder(tenant)

        TenantContext.set(tenant)
        val first = pixPaymentService.createPixCharge(orderId)
        TenantContext.set(tenant)
        val second = pixPaymentService.createPixCharge(orderId)

        assertEquals(first.id, second.id, "a 2a chamada deve devolver a MESMA cobranca pendente")
        assertEquals("copia-e-cola", first.pixCopyPaste)
        assertEquals(2_500, first.amountCents)
        // Asaas chamado uma unica vez (cobranca + customer + QR), nao duplicado.
        Mockito.verify(asaasClient, Mockito.times(1)).createPayment(anyArg<AsaasPaymentRequest>())
        Mockito.verify(asaasClient, Mockito.times(1)).getPixQr(Mockito.anyString())
        Mockito.verify(asaasClient, Mockito.times(1)).createCustomer(anyArg<AsaasCustomerRequest>())
    }

    @Test
    fun `webhook PAYMENT_RECEIVED marca pedido como PAID e reentrega do mesmo evento e no-op`() {
        val tenant = "pix_${UUID.randomUUID().toString().take(8)}"
        stubAsaas("pay_paid")
        val orderId = seedOrder(tenant)

        TenantContext.set(tenant)
        pixPaymentService.createPixCharge(orderId)

        val body = AsaasWebhookBody(
            id = "evt_001",
            event = "PAYMENT_RECEIVED",
            payment = AsaasPaymentResponse(
                id = "pay_paid",
                status = "RECEIVED",
                value = 25.0,
                externalReference = orderId.toString(),
            ),
        )

        TenantContext.set(tenant)
        pixPaymentService.handleWebhook(body)

        TenantContext.set(tenant)
        val orderAfter = orderRepository.findById(orderId).get()
        assertEquals(PaymentStatus.PAID, orderAfter.paymentStatus)
        val intentAfter = paymentIntentRepository.findByAsaasPaymentId("pay_paid")!!
        assertEquals(PaymentIntentStatus.PAID, intentAfter.status)
        val paidAtFirst = intentAfter.paidAt

        // Reentrega do MESMO evento: deduplicado, nenhum efeito novo.
        TenantContext.set(tenant)
        pixPaymentService.handleWebhook(body)
        TenantContext.set(tenant)
        val intentReplay = paymentIntentRepository.findByAsaasPaymentId("pay_paid")!!
        assertEquals(PaymentIntentStatus.PAID, intentReplay.status)
        assertEquals(paidAtFirst, intentReplay.paidAt, "reentrega nao deve re-tocar a cobranca")
    }
}
