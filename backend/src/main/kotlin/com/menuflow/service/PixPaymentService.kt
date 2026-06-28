package com.menuflow.service

import com.menuflow.client.AsaasClient
import com.menuflow.client.AsaasCustomerRequest
import com.menuflow.client.AsaasPaymentRequest
import com.menuflow.dto.AsaasWebhookBody
import com.menuflow.dto.PaymentIntentResponse
import com.menuflow.exception.ConflictException
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.model.PaymentIntent
import com.menuflow.model.PaymentIntentStatus
import com.menuflow.model.PaymentMethod
import com.menuflow.model.PaymentStatus
import com.menuflow.model.TenantConfig
import com.menuflow.repository.tenant.OrderRepository
import com.menuflow.repository.tenant.PaymentIntentRepository
import com.menuflow.repository.tenant.TenantConfigRepository
import com.menuflow.repository.tenant.WebhookEventRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Pagamento PIX via Asaas (Fase 2.3). Tudo roda no banco do TENANT
 * (tenantTransactionManager): PaymentIntent, deduplicacao de webhook e o pedido
 * vivem no mesmo banco, entao a atomicidade e o isolamento sao naturais.
 *
 * Idempotencia em DOIS pontos:
 *  - createPixCharge: ja existe cobranca PENDENTE do pedido -> devolve a mesma
 *    (nao cria outra no Asaas; protege double-click/retry).
 *  - handleWebhook: reentrega do mesmo evento (webhook_events.event_id UNIQUE) e
 *    no-op; cobranca ja PAID e no-op.
 */
@Service
class PixPaymentService(
    private val asaasClient: AsaasClient,
    private val paymentIntentRepository: PaymentIntentRepository,
    private val webhookEventRepository: WebhookEventRepository,
    private val orderRepository: OrderRepository,
    private val configRepository: TenantConfigRepository,
    private val auditLogService: AuditLogService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val saoPaulo = ZoneId.of("America/Sao_Paulo")
    private val pixTtl = Duration.ofMinutes(30)

    /**
     * Cria (ou recupera) a cobranca PIX de um pedido. Idempotente: se ja ha uma
     * cobranca PENDENTE para o pedido, devolve-a sem chamar o Asaas de novo.
     */
    @Transactional("tenantTransactionManager")
    fun createPixCharge(orderId: UUID): PaymentIntentResponse {
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResourceNotFoundException("Pedido nao encontrado: $orderId") }
        if (order.paymentStatus == PaymentStatus.PAID) {
            throw ConflictException("Pedido ja esta pago")
        }

        // Idempotencia: reaproveita uma cobranca pendente existente.
        paymentIntentRepository.findByOrderIdOrderByCreatedAtDesc(orderId)
            .firstOrNull { it.status == PaymentIntentStatus.PENDING }
            ?.let { return PaymentIntentResponse.from(it) }

        // Customer avulso fixo do tenant (criado uma vez, guardado no TenantConfig).
        val customerId = ensureCustomer()

        // Asaas usa valor decimal em reais; converte de centavos sem float impreciso.
        val valueReais = BigDecimal(order.totalCents).movePointLeft(2).toDouble()
        val dueDate = LocalDate.now(saoPaulo).plusDays(1).toString() // "YYYY-MM-DD"

        val payment = asaasClient.createPayment(
            AsaasPaymentRequest(
                customer = customerId,
                value = valueReais,
                dueDate = dueDate,
                externalReference = orderId.toString(),
                description = "Pedido ${order.orderNumber}",
            ),
        )
        val qr = asaasClient.getPixQr(payment.id)

        val intent = paymentIntentRepository.save(
            PaymentIntent(
                orderId = orderId,
                asaasPaymentId = payment.id,
                status = PaymentIntentStatus.PENDING,
                amountCents = order.totalCents,
                pixQrImage = qr.encodedImage,
                pixCopyPaste = qr.payload,
                expiresAt = Instant.now().plus(pixTtl),
            ),
        )
        return PaymentIntentResponse.from(intent)
    }

    /** Status atual da cobranca de um pedido (polling do front). */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun getStatus(orderId: UUID): PaymentIntentResponse {
        val intent = paymentIntentRepository.findByOrderIdOrderByCreatedAtDesc(orderId)
            .firstOrNull()
            ?: throw ResourceNotFoundException("Sem cobranca PIX para o pedido: $orderId")
        return PaymentIntentResponse.from(intent)
    }

    /**
     * Processa o webhook do Asaas. O chamador (controller) ja vinculou o
     * TenantContext pelo slug do path, entao tudo aqui roteia para o banco daquele
     * tenant. Idempotente e isolado: uma cobranca de OUTRO tenant simplesmente nao
     * existe neste banco (findByAsaasPaymentId == null) e o evento e ignorado.
     */
    @Transactional("tenantTransactionManager")
    fun handleWebhook(body: AsaasWebhookBody) {
        // 1. Deduplicacao: mesmo evento ja processado -> no-op.
        if (webhookEventRepository.existsByEventId(body.id)) {
            log.debug("Webhook Asaas duplicado ignorado: {}", body.id)
            return
        }
        webhookEventRepository.save(com.menuflow.model.WebhookEvent(eventId = body.id))

        // 2. So nos interessa o PIX confirmado.
        if (body.event != "PAYMENT_RECEIVED") {
            log.debug("Webhook Asaas ignorado (evento {})", body.event)
            return
        }

        // 3. Resolve a cobranca pelo id do Asaas. Ausente = pagamento de outro
        //    tenant (isolamento db-per-tenant) ou desconhecido -> ignora.
        val intent = paymentIntentRepository.findByAsaasPaymentId(body.payment.id)
        if (intent == null) {
            log.warn("Webhook PAYMENT_RECEIVED para cobranca desconhecida neste tenant: {}", body.payment.id)
            return
        }
        // 4. Defesa extra: a referencia externa deve apontar para o mesmo pedido.
        val externalRef = body.payment.externalReference
        if (externalRef != null && externalRef != intent.orderId.toString()) {
            log.warn("Webhook com externalReference divergente ({} != {})", externalRef, intent.orderId)
            return
        }
        // 5. Idempotencia de efeito: cobranca ja paga -> no-op.
        if (intent.status == PaymentIntentStatus.PAID) return

        // 6. Marca cobranca e pedido como pagos.
        intent.status = PaymentIntentStatus.PAID
        intent.paidAt = Instant.now()
        paymentIntentRepository.save(intent)

        orderRepository.findById(intent.orderId).ifPresent { order ->
            order.paymentStatus = PaymentStatus.PAID
            order.paymentMethod = PaymentMethod.PIX
            orderRepository.save(order)
        }

        // Auditoria (pula em silencio se nao houver ator resolvivel — webhook nao
        // tem principal; e o comportamento padrao do AuditLogService).
        auditLogService.log(
            action = "order.pix_paid",
            entity = "order",
            entityId = intent.orderId,
            after = mapOf("asaasPaymentId" to body.payment.id, "amountCents" to intent.amountCents),
        )
    }

    /**
     * Reconciliacao: marca como EXPIRED as cobrancas PENDENTES ja vencidas do tenant
     * atual. Chamada pelo PixReconciliationJob com o TenantContext ja vinculado.
     * Retorna quantas expiraram.
     */
    @Transactional("tenantTransactionManager")
    fun expireOverdueForCurrentTenant(): Int {
        val overdue = paymentIntentRepository
            .findAllByStatusAndExpiresAtBefore(PaymentIntentStatus.PENDING, Instant.now())
        overdue.forEach { it.status = PaymentIntentStatus.EXPIRED }
        if (overdue.isNotEmpty()) paymentIntentRepository.saveAll(overdue)
        return overdue.size
    }

    /**
     * Garante o customer avulso fixo do tenant no Asaas: le do TenantConfig; se nao
     * houver, cria no Asaas e persiste o id. Nome derivado da cidade/nome do
     * restaurante, com fallback generico.
     */
    private fun ensureCustomer(): String {
        val config = configRepository.findFirstByOrderByCreatedAtAsc() ?: TenantConfig()
        config.asaasCustomerId?.let { return it }

        val name = config.merchantCity?.ifBlank { null }
            ?: config.restaurantName?.ifBlank { null }
            ?: "MenuFlow PDV"
        val customer = asaasClient.createCustomer(AsaasCustomerRequest(name = name))
        config.asaasCustomerId = customer.id
        configRepository.save(config)
        return customer.id
    }
}
