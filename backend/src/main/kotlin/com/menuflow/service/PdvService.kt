package com.menuflow.service

import com.menuflow.dto.OrderCreateRequest
import com.menuflow.dto.OrderResponse
import com.menuflow.dto.PaymentResponse
import com.menuflow.dto.PdvOrderCreateRequest
import com.menuflow.dto.PdvPaymentRequest
import com.menuflow.exception.BusinessException
import com.menuflow.exception.ConflictException
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.event.OrderPaidEvent
import com.menuflow.exception.UnprocessableEntityException
import com.menuflow.model.CashSessionStatus
import com.menuflow.model.OrderStatus
import com.menuflow.model.Payment
import com.menuflow.model.PaymentStatus
import com.menuflow.model.PdvPaymentMethod
import com.menuflow.repository.tenant.CashSessionRepository
import com.menuflow.repository.tenant.OrderRepository
import com.menuflow.repository.tenant.PaymentRepository
import com.menuflow.tenant.TenantContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Point of Sale (Sprint 2). Order creation REUSES [OrderService.create] (price
 * snapshot + atomic stock decrement via ficha técnica). Payment registers a
 * [Payment] and moves the order to DELIVERED. Money is in CENTAVOS throughout.
 */
@Service
class PdvService(
    private val orderService: OrderService,
    private val orderRepository: OrderRepository,
    private val paymentRepository: PaymentRepository,
    private val cashSessionRepository: CashSessionRepository,
    private val eventPublisher: ApplicationEventPublisher,
) {

    /** Creates a PDV order, delegating to the shared atomic order-creation logic. */
    @Transactional("tenantTransactionManager")
    fun createOrder(req: PdvOrderCreateRequest, userId: UUID?): OrderResponse {
        val orderReq = OrderCreateRequest(
            orderType = req.channel.toOrderType(),
            tableNumber = req.tableNumber,
            items = req.items,
        )
        return orderService.create(orderReq, userId)
    }

    /**
     * Registers a payment for an order and closes it (DELIVERED).
     *
     * - amountPaidCents < totalCents -> 422 (underpayment is not allowed).
     * - change (troco) = amountPaid - total for CASH; 0 for CARD/PIX.
     * - paying a CANCELLED order or an already-paid order -> 400.
     * - CASH sem caixa aberto -> 409 (a venda em dinheiro precisa entrar num turno).
     *
     * Caixa: o PDV cria o pedido SEM paymentMethod e só define a forma de pagamento
     * aqui, no pay(). Por isso o carimbo do turno (cashSessionId) acontece NESTE
     * ponto — não no OrderService.create, que para o PDV vê paymentMethod=null. Sem
     * o carimbo, a venda em dinheiro do balcão sumiria do esperado do caixa
     * (sumCashSalesForSession exige cashSessionId + CASH + PAID).
     *
     * Atomic: payment row + order state change commit together.
     */
    @Transactional("tenantTransactionManager")
    fun pay(orderId: UUID, req: PdvPaymentRequest): PaymentResponse {
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResourceNotFoundException("Order not found: $orderId") }

        if (order.status == OrderStatus.CANCELLED) {
            throw BusinessException("Cannot pay a cancelled order")
        }
        if (order.paymentStatus == PaymentStatus.PAID) {
            throw BusinessException("Order is already paid")
        }
        if (req.amountPaidCents < order.totalCents) {
            throw UnprocessableEntityException(
                "Insufficient amount paid",
                listOf(
                    mapOf(
                        "totalCents" to order.totalCents,
                        "amountPaidCents" to req.amountPaidCents,
                        "shortfallCents" to (order.totalCents - req.amountPaidCents),
                    ),
                ),
            )
        }

        // Venda em dinheiro: precisa de um turno aberto e carimba o pedido com ele,
        // para entrar no esperado da gaveta. Cartão/PIX não tocam o caixa.
        if (req.method == PdvPaymentMethod.CASH) {
            val session = cashSessionRepository.findFirstByStatus(CashSessionStatus.OPEN)
                ?: throw ConflictException("Abra o caixa para registrar vendas em dinheiro")
            order.cashSessionId = session.id
        }

        val change = if (req.method == PdvPaymentMethod.CASH) {
            req.amountPaidCents - order.totalCents
        } else {
            0L
        }

        val payment = paymentRepository.save(
            Payment(
                orderId = order.id!!,
                method = req.method,
                amountPaidCents = req.amountPaidCents,
                changeCents = change,
            ),
        )

        order.paymentStatus = PaymentStatus.PAID
        order.paymentMethod = when (req.method) {
            PdvPaymentMethod.CASH -> com.menuflow.model.PaymentMethod.CASH
            PdvPaymentMethod.CARD -> com.menuflow.model.PaymentMethod.CREDIT_CARD
            PdvPaymentMethod.PIX -> com.menuflow.model.PaymentMethod.PIX
        }
        // DRE (Fase 3.1): o PDV cria o pedido SEM forma de pagamento e só a define
        // aqui — então a taxa de cartão (snapshot do DRE) é calculada NESTE ponto,
        // não no create (que para o PDV viu paymentMethod=null). Mesmo raciocínio
        // do carimbo do caixa acima.
        order.cardFeeCents = orderService.computeCardFeeCents(order.totalCents, order.paymentMethod)
        order.status = OrderStatus.DELIVERED
        order.completedAt = Instant.now()
        orderRepository.save(order)

        // Espinha da Fase 3: o pedido acabou de ficar PAID -> publica o fato de
        // domínio DENTRO desta transação. Os listeners (fidelidade etc.) consomem
        // APÓS o commit (AFTER_COMMIT). O slug do tenant vem do TenantContext do
        // token assinado, necessário para rotear de volta no modelo db-per-tenant.
        eventPublisher.publishEvent(
            OrderPaidEvent(
                tenantSlug = TenantContext.getOrThrow(),
                orderId = order.id!!,
                customerId = order.customerId,
                customerPhone = order.customerPhone,
                totalCents = order.totalCents,
            ),
        )

        return PaymentResponse.from(payment, order.totalCents)
    }
}
