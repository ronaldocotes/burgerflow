package com.menuflow

import com.menuflow.dto.OpenSessionRequest
import com.menuflow.dto.OrderItemRequest
import com.menuflow.dto.PdvChannel
import com.menuflow.dto.PdvOrderCreateRequest
import com.menuflow.dto.PdvPaymentRequest
import com.menuflow.dto.ProductCreateRequest
import com.menuflow.exception.BusinessException
import com.menuflow.exception.UnprocessableEntityException
import com.menuflow.model.OrderStatus
import com.menuflow.model.PaymentStatus
import com.menuflow.model.PdvPaymentMethod
import com.menuflow.repository.tenant.OrderRepository
import com.menuflow.service.CashSessionService
import com.menuflow.service.PdvService
import com.menuflow.service.ProductService
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * PDV (Sprint 2) integration tests against a real tenant Postgres: order creation
 * reuses the atomic stock-decrement path; payment closes the order to DELIVERED,
 * computes troco for CASH, and refuses underpayment with 422.
 */
class PdvServiceTest @Autowired constructor(
    private val pdvService: PdvService,
    private val productService: ProductService,
    private val cashSessionService: CashSessionService,
    private val orderRepository: OrderRepository,
    private val tenantTx: TenantTestTx,
) : IntegrationTestBase() {

    private lateinit var tenant: String

    @BeforeEach
    fun bind() {
        tenant = "pdv_${UUID.randomUUID().toString().take(8)}"
        TenantContext.set(tenant)
    }

    @AfterEach
    fun clear() = TenantContext.clear()

    private fun newOrderId(priceCents: Long, qty: Int): UUID {
        TenantContext.set(tenant)
        val product = productService.create(
            ProductCreateRequest(
                categoryId = UUID.randomUUID(),
                sku = "P-${UUID.randomUUID().toString().take(6)}",
                name = "Burger",
                priceCents = priceCents,
            ),
        )
        return pdvService.createOrder(
            PdvOrderCreateRequest(
                items = listOf(OrderItemRequest(productId = product.id, quantity = qty)),
                channel = PdvChannel.TAKEOUT,
            ),
            userId = null,
        ).id
    }

    @Test
    fun `cash payment closes order as DELIVERED and returns change`() {
        // Venda em dinheiro exige caixa aberto: o pay(CASH) carimba o turno no pedido.
        TenantContext.set(tenant)
        cashSessionService.open(UUID.randomUUID(), OpenSessionRequest(openingAmountCents = 0, confirmZeroOpening = true))

        val orderId = newOrderId(priceCents = 2500, qty = 2) // total 5000

        val payment = pdvService.pay(
            orderId,
            PdvPaymentRequest(method = PdvPaymentMethod.CASH, amountPaidCents = 6000),
        )

        assertEquals(5000, payment.totalCents)
        assertEquals(6000, payment.amountPaidCents)
        assertEquals(1000, payment.changeCents) // troco

        tenantTx.run {
            TenantContext.set(tenant)
            val order = orderRepository.findById(orderId).get()
            assertEquals(OrderStatus.DELIVERED, order.status)
            assertEquals(PaymentStatus.PAID, order.paymentStatus)
        }
    }

    @Test
    fun `card payment has zero change`() {
        val orderId = newOrderId(priceCents = 3000, qty = 1) // total 3000
        val payment = pdvService.pay(
            orderId,
            PdvPaymentRequest(method = PdvPaymentMethod.CARD, amountPaidCents = 3000),
        )
        assertEquals(0, payment.changeCents)
    }

    @Test
    fun `underpayment is rejected with 422 and does not close the order`() {
        val orderId = newOrderId(priceCents = 4000, qty = 1) // total 4000

        assertThrows(UnprocessableEntityException::class.java) {
            pdvService.pay(
                orderId,
                PdvPaymentRequest(method = PdvPaymentMethod.PIX, amountPaidCents = 3999),
            )
        }

        tenantTx.run {
            TenantContext.set(tenant)
            val order = orderRepository.findById(orderId).get()
            assertEquals(PaymentStatus.PENDING, order.paymentStatus)
            assertEquals(OrderStatus.PENDING, order.status)
        }
    }

    @Test
    fun `paying an already-paid order is rejected`() {
        val orderId = newOrderId(priceCents = 1000, qty = 1)
        pdvService.pay(orderId, PdvPaymentRequest(PdvPaymentMethod.PIX, 1000))
        assertThrows(BusinessException::class.java) {
            pdvService.pay(orderId, PdvPaymentRequest(PdvPaymentMethod.PIX, 1000))
        }
    }
}
