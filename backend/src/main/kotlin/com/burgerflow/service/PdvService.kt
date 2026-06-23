package com.burgerflow.service

import com.burgerflow.dto.OrderCreateRequest
import com.burgerflow.dto.OrderResponse
import com.burgerflow.dto.PaymentResponse
import com.burgerflow.dto.PdvOrderCreateRequest
import com.burgerflow.dto.PdvPaymentRequest
import com.burgerflow.exception.BusinessException
import com.burgerflow.exception.ResourceNotFoundException
import com.burgerflow.exception.UnprocessableEntityException
import com.burgerflow.model.OrderStatus
import com.burgerflow.model.Payment
import com.burgerflow.model.PaymentStatus
import com.burgerflow.model.PdvPaymentMethod
import com.burgerflow.repository.tenant.OrderRepository
import com.burgerflow.repository.tenant.PaymentRepository
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
            PdvPaymentMethod.CASH -> com.burgerflow.model.PaymentMethod.CASH
            PdvPaymentMethod.CARD -> com.burgerflow.model.PaymentMethod.CREDIT_CARD
            PdvPaymentMethod.PIX -> com.burgerflow.model.PaymentMethod.PIX
        }
        order.status = OrderStatus.DELIVERED
        order.completedAt = Instant.now()
        orderRepository.save(order)

        return PaymentResponse.from(payment, order.totalCents)
    }
}
