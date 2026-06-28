package com.menuflow

import com.menuflow.dto.CloseSessionRequest
import com.menuflow.dto.EntryRequest
import com.menuflow.dto.OpenSessionRequest
import com.menuflow.dto.OrderCreateRequest
import com.menuflow.dto.OrderItemRequest
import com.menuflow.dto.PdvPaymentRequest
import com.menuflow.dto.ProductCreateRequest
import com.menuflow.exception.ConflictException
import com.menuflow.model.CashEntryType
import com.menuflow.model.CashSessionStatus
import com.menuflow.model.PaymentMethod
import com.menuflow.model.PdvPaymentMethod
import com.menuflow.service.CashSessionService
import com.menuflow.service.OrderService
import com.menuflow.service.PdvService
import com.menuflow.service.ProductService
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Turno de caixa (CashSession) contra um Postgres real (Testcontainers).
 *
 *  - Fluxo completo: abrir -> vender em dinheiro (carimba o turno) -> sangria ->
 *    reforço -> fechar; o esperado e a diferença fecham com aritmética de centavos.
 *  - Segundo open com turno já aberto -> 409 (ConflictException).
 *  - Pedido em dinheiro do operador sem caixa aberto -> 409.
 *
 * Cada caso usa seu PRÓPRIO tenant (db isolado). O teste não é @Transactional, então
 * cada chamada de serviço commita (como PdvServiceTest), permitindo que create veja
 * o turno aberto por open numa transação anterior.
 */
class CashSessionTest @Autowired constructor(
    private val cashSessionService: CashSessionService,
    private val orderService: OrderService,
    private val pdvService: PdvService,
    private val productService: ProductService,
) : IntegrationTestBase() {

    @AfterEach
    fun clear() = TenantContext.clear()

    private fun seedProduct(tenant: String, priceCents: Long): UUID {
        TenantContext.set(tenant)
        return productService.create(
            ProductCreateRequest(
                categoryId = UUID.randomUUID(),
                sku = "P-${UUID.randomUUID().toString().take(6)}",
                name = "Burger",
                priceCents = priceCents,
            ),
        ).id
    }

    @Test
    fun `full flow - open, cash sale, withdrawal, deposit, close with correct difference`() {
        val tenant = "cash_${UUID.randomUUID().toString().take(8)}"
        val actor = UUID.randomUUID()
        TenantContext.set(tenant)

        // Abre o caixa com R$100,00 de troco inicial.
        val opened = cashSessionService.open(actor, OpenSessionRequest(openingAmountCents = 10_000))
        assertEquals(CashSessionStatus.OPEN, opened.status)
        val sessionId = opened.id

        // Venda em dinheiro: pedido CASH carimba o turno; pagamento marca PAID.
        val productId = seedProduct(tenant, priceCents = 2_500)
        TenantContext.set(tenant)
        val order = orderService.create(
            OrderCreateRequest(
                items = listOf(OrderItemRequest(productId = productId, quantity = 2)), // total 5000
                paymentMethod = PaymentMethod.CASH,
            ),
            userId = actor,
        )
        assertEquals(5_000, order.totalCents)
        pdvService.pay(order.id, PdvPaymentRequest(method = PdvPaymentMethod.CASH, amountPaidCents = 5_000))

        // Sangria R$20,00 e reforço R$10,00.
        TenantContext.set(tenant)
        cashSessionService.addEntry(
            sessionId, actor, EntryRequest(CashEntryType.WITHDRAWAL, 2_000, "Pagamento do motoboy"),
        )
        cashSessionService.addEntry(
            sessionId, actor, EntryRequest(CashEntryType.DEPOSIT, 1_000, "Troco extra"),
        )

        // Esperado = 10000 + 5000 (venda) + 1000 (reforço) - 2000 (sangria) = 14000.
        // Conta 13950 -> diferença -50 (falta no caixa).
        TenantContext.set(tenant)
        val closed = cashSessionService.close(
            sessionId, actor, CloseSessionRequest(countedAmountCents = 13_950),
        )

        assertEquals(CashSessionStatus.CLOSED, closed.status)
        assertEquals(5_000, closed.cashSalesCents)
        assertEquals(1_000, closed.depositsCents)
        assertEquals(2_000, closed.withdrawalsCents)
        assertEquals(14_000, closed.expectedCents)
        assertEquals(13_950, closed.countedCents)
        assertEquals(-50, closed.differenceCents)
        assertEquals(2, closed.entries.size)
    }

    @Test
    fun `opening a second session while one is open returns 409`() {
        val tenant = "cash2_${UUID.randomUUID().toString().take(8)}"
        val actor = UUID.randomUUID()
        TenantContext.set(tenant)

        cashSessionService.open(actor, OpenSessionRequest(openingAmountCents = 0))

        TenantContext.set(tenant)
        assertThrows(ConflictException::class.java) {
            cashSessionService.open(actor, OpenSessionRequest(openingAmountCents = 0))
        }
    }

    @Test
    fun `cash order by an operator with no open register is rejected with 409`() {
        val tenant = "cash3_${UUID.randomUUID().toString().take(8)}"
        val productId = seedProduct(tenant, priceCents = 1_000)

        TenantContext.set(tenant)
        assertThrows(ConflictException::class.java) {
            orderService.create(
                OrderCreateRequest(
                    items = listOf(OrderItemRequest(productId = productId, quantity = 1)),
                    paymentMethod = PaymentMethod.CASH,
                ),
                userId = UUID.randomUUID(),
            )
        }
    }
}
