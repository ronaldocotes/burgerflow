package com.menuflow

import com.menuflow.dto.CloseSessionRequest
import com.menuflow.dto.EntryRequest
import com.menuflow.dto.OpenSessionRequest
import com.menuflow.dto.OrderCreateRequest
import com.menuflow.dto.OrderItemRequest
import com.menuflow.dto.PdvChannel
import com.menuflow.dto.PdvOrderCreateRequest
import com.menuflow.dto.PdvPaymentRequest
import com.menuflow.dto.ProductCreateRequest
import com.menuflow.dto.ReconciliationMethod
import com.menuflow.exception.ConflictException
import com.menuflow.exception.UnprocessableEntityException
import com.menuflow.model.CashEntryType
import com.menuflow.model.CashSessionStatus
import com.menuflow.model.PaymentMethod
import com.menuflow.model.PaymentStatus
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

        // Venda em dinheiro pelo fluxo do PDV web/app: POST /orders com paymentMethod=CASH
        // e operador autenticado -> o pedido nasce PAID e carimbado com o turno, SEM um
        // pay() posterior. Antes ficava PENDING e sumia do caixa (bug P1).
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
        assertEquals(PaymentStatus.PAID, order.paymentStatus)

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

        cashSessionService.open(actor, OpenSessionRequest(openingAmountCents = 0, confirmZeroOpening = true))

        TenantContext.set(tenant)
        assertThrows(ConflictException::class.java) {
            cashSessionService.open(actor, OpenSessionRequest(openingAmountCents = 0, confirmZeroOpening = true))
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

    /**
     * Fluxo /pdv REAL: cria o pedido SEM forma de pagamento (PdvService.createOrder) e
     * define a forma só no pay(), que marca PAID e carimba o turno para TODAS as formas.
     * Distinto do fluxo do PDV web/app (POST /orders com paymentMethod, que já nasce
     * pago no create) — aqui exercitamos justamente o caminho do pay().
     */
    private fun sellAndPay(tenant: String, productId: UUID, actor: UUID, qty: Int, method: PdvPaymentMethod) {
        TenantContext.set(tenant)
        val order = pdvService.createOrder(
            PdvOrderCreateRequest(
                items = listOf(OrderItemRequest(productId = productId, quantity = qty)),
                channel = PdvChannel.TAKEOUT,
            ),
            userId = actor,
        )
        pdvService.pay(order.id, PdvPaymentRequest(method = method, amountPaidCents = order.totalCents))
    }

    @Test
    fun `reconciliation per payment method, suggested next opening and withdrawal at close`() {
        val tenant = "cashrec_${UUID.randomUUID().toString().take(8)}"
        val actor = UUID.randomUUID()
        TenantContext.set(tenant)

        // Abre com R$100 de troco. Vende R$50 dinheiro, R$30 cartão, R$20 pix.
        val opened = cashSessionService.open(actor, OpenSessionRequest(openingAmountCents = 10_000))
        val sessionId = opened.id
        val productId = seedProduct(tenant, priceCents = 1_000)
        sellAndPay(tenant, productId, actor, qty = 5, method = PdvPaymentMethod.CASH) // 5000
        sellAndPay(tenant, productId, actor, qty = 3, method = PdvPaymentMethod.CARD) // 3000
        sellAndPay(tenant, productId, actor, qty = 2, method = PdvPaymentMethod.PIX) // 2000

        // Preview enquanto aberto: esperado por forma, sem contado.
        TenantContext.set(tenant)
        val preview = cashSessionService.get(sessionId)
        val previewCash = preview.reconciliation.first { it.method == ReconciliationMethod.CASH }
        assertEquals(15_000, previewCash.expectedCents) // 10000 abertura + 5000 dinheiro
        assertEquals(null, previewCash.countedCents)
        assertEquals(3_000, preview.reconciliation.first { it.method == ReconciliationMethod.CARD }.expectedCents)
        assertEquals(2_000, preview.reconciliation.first { it.method == ReconciliationMethod.PIX }.expectedCents)

        // Fecha: dinheiro confere (15000), cartão confere (3000), pix falta 100 (1900),
        // retira R$140 do caixa -> sobra sugerida = 15000 - 14000 = 1000.
        TenantContext.set(tenant)
        val closed = cashSessionService.close(
            sessionId, actor,
            CloseSessionRequest(
                countedAmountCents = 15_000,
                countedCardCents = 3_000,
                countedPixCents = 1_900,
                withdrawnAmountCents = 14_000,
            ),
        )

        assertEquals(CashSessionStatus.CLOSED, closed.status)
        val cash = closed.reconciliation.first { it.method == ReconciliationMethod.CASH }
        assertEquals(15_000, cash.expectedCents)
        assertEquals(15_000, cash.countedCents)
        assertEquals(0, cash.differenceCents)
        val card = closed.reconciliation.first { it.method == ReconciliationMethod.CARD }
        assertEquals(3_000, card.expectedCents)
        assertEquals(3_000, card.countedCents)
        assertEquals(0, card.differenceCents)
        val pix = closed.reconciliation.first { it.method == ReconciliationMethod.PIX }
        assertEquals(2_000, pix.expectedCents)
        assertEquals(1_900, pix.countedCents)
        assertEquals(-100, pix.differenceCents) // falta 1 real no pix

        assertEquals(14_000, closed.withdrawnAtCloseCents)
        assertEquals(1_000, closed.suggestedNextOpeningCents) // 15000 - 14000
    }

    @Test
    fun `operator sale created with card is PAID and enters per-method reconciliation`() {
        // Regressao do bug P1 para credito/debito: no PDV web/app a venda com cartao vai
        // por POST /orders com paymentMethod=CREDIT_CARD (sem pay() posterior). O pedido
        // deve nascer PAID e ser carimbado com o turno para entrar na reconciliacao por
        // forma (cartao). Antes ficava PENDING e sumia do fechamento.
        val tenant = "cashcard_${UUID.randomUUID().toString().take(8)}"
        val actor = UUID.randomUUID()
        TenantContext.set(tenant)
        val opened = cashSessionService.open(actor, OpenSessionRequest(openingAmountCents = 0, confirmZeroOpening = true))

        val productId = seedProduct(tenant, priceCents = 3_000)
        TenantContext.set(tenant)
        val order = orderService.create(
            OrderCreateRequest(
                items = listOf(OrderItemRequest(productId = productId, quantity = 1)), // total 3000
                paymentMethod = PaymentMethod.CREDIT_CARD,
            ),
            userId = actor,
        )
        assertEquals(PaymentStatus.PAID, order.paymentStatus)

        // Reconciliacao por forma: o cartao esperado do turno passa a contar a venda.
        TenantContext.set(tenant)
        val preview = cashSessionService.get(opened.id)
        assertEquals(
            3_000,
            preview.reconciliation.first { it.method == ReconciliationMethod.CARD }.expectedCents,
        )
    }

    @Test
    fun `opening with zero and no explicit confirmation is rejected`() {
        val tenant = "cashzero_${UUID.randomUUID().toString().take(8)}"
        val actor = UUID.randomUUID()
        TenantContext.set(tenant)

        assertThrows(UnprocessableEntityException::class.java) {
            cashSessionService.open(actor, OpenSessionRequest(openingAmountCents = 0))
        }

        // Com confirmação explícita a abertura zerada passa.
        TenantContext.set(tenant)
        val opened = cashSessionService.open(actor, OpenSessionRequest(openingAmountCents = 0, confirmZeroOpening = true))
        assertEquals(CashSessionStatus.OPEN, opened.status)
        assertEquals(0, opened.openingAmountCents)
    }

    @Test
    fun `withdrawing more than counted cash at close is rejected`() {
        val tenant = "cashwd_${UUID.randomUUID().toString().take(8)}"
        val actor = UUID.randomUUID()
        TenantContext.set(tenant)

        val opened = cashSessionService.open(actor, OpenSessionRequest(openingAmountCents = 5_000))

        TenantContext.set(tenant)
        assertThrows(UnprocessableEntityException::class.java) {
            cashSessionService.close(
                opened.id, actor,
                CloseSessionRequest(countedAmountCents = 5_000, withdrawnAmountCents = 6_000),
            )
        }
    }
}
