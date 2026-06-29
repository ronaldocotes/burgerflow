package com.menuflow

import com.menuflow.dto.IngredientRequest
import com.menuflow.dto.OrderCreateRequest
import com.menuflow.dto.OrderItemRequest
import com.menuflow.dto.ProductCreateRequest
import com.menuflow.dto.RecipeItemRequest
import com.menuflow.model.ExpenseCategory
import com.menuflow.model.OperatingExpense
import com.menuflow.model.Order
import com.menuflow.model.OrderStatus
import com.menuflow.model.OrderType
import com.menuflow.model.PaymentMethod
import com.menuflow.model.SalesChannel
import com.menuflow.repository.tenant.OperatingExpenseRepository
import com.menuflow.repository.tenant.OrderRepository
import com.menuflow.repository.tenant.TenantConfigRepository
import com.menuflow.service.DreService
import com.menuflow.service.IngredientService
import com.menuflow.service.OrderService
import com.menuflow.service.ProductRecipeService
import com.menuflow.service.ProductService
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * DRE Automático (Fase 3.1) contra um Postgres real (Testcontainers). Prova:
 *  - totais corretos com pedidos de múltiplos canais/formas de pagamento, só
 *    contando DELIVERED no período;
 *  - período sem pedidos -> tudo zero (sem NPE);
 *  - despesas dentro do período contam, fora não;
 *  - snapshot no create: marketplace só em DELIVERY, cartão só em cartão, canal
 *    derivado; e CMV (cogs) gravado a partir da ficha técnica.
 *
 * Cada caso usa seu PRÓPRIO tenant (db isolado). Não é @Transactional: cada save
 * commita, então o compute() enxerga o que foi semeado antes.
 */
class DreServiceTest @Autowired constructor(
    private val dreService: DreService,
    private val orderService: OrderService,
    private val productService: ProductService,
    private val ingredientService: IngredientService,
    private val recipeService: ProductRecipeService,
    private val orderRepository: OrderRepository,
    private val operatingExpenseRepository: OperatingExpenseRepository,
    private val tenantConfigRepository: TenantConfigRepository,
) : IntegrationTestBase() {

    private val zone = ZoneId.of("America/Sao_Paulo")

    @AfterEach
    fun clear() = TenantContext.clear()

    private fun bind(): String {
        val tenant = "dre_${UUID.randomUUID().toString().take(8)}"
        TenantContext.set(tenant)
        return tenant
    }

    /** Persiste um pedido DELIVERED com os snapshots do DRE já carimbados. */
    private fun seedOrder(
        channel: SalesChannel,
        method: PaymentMethod?,
        total: Long,
        cogs: Long,
        marketplace: Long,
        card: Long,
        status: OrderStatus = OrderStatus.DELIVERED,
        completedAt: Instant? = Instant.now(),
    ) {
        orderRepository.save(
            Order(
                orderNumber = "DRE-${UUID.randomUUID().toString().take(10)}",
                status = status,
                salesChannel = channel,
                paymentMethod = method,
                subtotalCents = total,
                totalCents = total,
                cogsCents = cogs,
                marketplaceFeeCents = marketplace,
                cardFeeCents = card,
                completedAt = completedAt,
            ),
        )
    }

    private fun setTaxPct(pct: Long) {
        val config = tenantConfigRepository.findFirstByOrderByCreatedAtAsc()!!
        config.taxPct = BigDecimal(pct)
        tenantConfigRepository.save(config)
    }

    private fun setFees(marketplacePct: Long, cardPct: Long) {
        val config = tenantConfigRepository.findFirstByOrderByCreatedAtAsc()!!
        config.marketplaceFeePct = BigDecimal(marketplacePct)
        config.cardFeePct = BigDecimal(cardPct)
        tenantConfigRepository.save(config)
    }

    @Test
    fun `dre - multi-channel orders produce correct cascade and breakdowns`() {
        bind()
        setTaxPct(10) // imposto 10% sobre a receita bruta

        // 3 vendas DELIVERED hoje, canais e formas distintas.
        seedOrder(SalesChannel.DELIVERY, PaymentMethod.PIX, total = 10_000, cogs = 4_000, marketplace = 1_500, card = 0)
        seedOrder(SalesChannel.DINE_IN, PaymentMethod.CREDIT_CARD, total = 5_000, cogs = 2_000, marketplace = 0, card = 250)
        seedOrder(SalesChannel.COUNTER, PaymentMethod.CASH, total = 3_000, cogs = 1_000, marketplace = 0, card = 0)
        // Ruído: PENDING (não conta) e DELIVERED fora do período.
        seedOrder(SalesChannel.COUNTER, PaymentMethod.CASH, total = 9_999, cogs = 9_999, marketplace = 0, card = 0, status = OrderStatus.PENDING)
        seedOrder(SalesChannel.DELIVERY, PaymentMethod.PIX, total = 7_777, cogs = 1, marketplace = 1, card = 0, completedAt = Instant.now().minus(10, ChronoUnit.DAYS))

        // Despesa operacional no período.
        operatingExpenseRepository.save(
            OperatingExpense(description = "Aluguel", amountCents = 2_000, category = ExpenseCategory.RENT, expenseDate = LocalDate.now(zone)),
        )

        val today = LocalDate.now(zone)
        val dre = dreService.compute(today, today)

        assertEquals(18_000, dre.grossRevenueCents, "10000+5000+3000")
        assertEquals(1_500, dre.marketplaceFeesCents)
        assertEquals(250, dre.cardFeesCents)
        assertEquals(1_800, dre.taxCents, "18000 * 10%")
        assertEquals(14_450, dre.netRevenueCents, "18000 - 1500 - 250 - 1800")
        assertEquals(7_000, dre.cogsCents, "4000+2000+1000")
        assertEquals(7_450, dre.grossProfitCents, "14450 - 7000")
        assertEquals(2_000, dre.operatingExpensesCents)
        assertEquals(5_450, dre.netProfitCents, "7450 - 2000")
        assertEquals(3, dre.orderCount)
        assertEquals(6_000, dre.averageTicketCents, "18000 / 3")
        assertEquals(41.39, dre.grossMarginPct, 0.01)
        assertEquals(30.28, dre.netMarginPct, 0.01)

        assertEquals(1, dre.ordersByChannel["DELIVERY"])
        assertEquals(1, dre.ordersByChannel["DINE_IN"])
        assertEquals(1, dre.ordersByChannel["COUNTER"])
        assertEquals(1, dre.ordersByPaymentMethod["PIX"])
        assertEquals(1, dre.ordersByPaymentMethod["CREDIT_CARD"])
        assertEquals(1, dre.ordersByPaymentMethod["CASH"])
    }

    @Test
    fun `dre - empty period returns all zeros without error`() {
        bind()
        val today = LocalDate.now(zone)
        val dre = dreService.compute(today, today)

        assertEquals(0, dre.grossRevenueCents)
        assertEquals(0, dre.netRevenueCents)
        assertEquals(0, dre.cogsCents)
        assertEquals(0, dre.grossProfitCents)
        assertEquals(0, dre.operatingExpensesCents)
        assertEquals(0, dre.netProfitCents)
        assertEquals(0, dre.orderCount)
        assertEquals(0, dre.averageTicketCents)
        assertEquals(0.0, dre.grossMarginPct, 0.0001)
        assertEquals(0.0, dre.netMarginPct, 0.0001)
        assertEquals(true, dre.ordersByChannel.isEmpty())
        assertEquals(true, dre.ordersByPaymentMethod.isEmpty())
    }

    @Test
    fun `dre - operating expenses only count within the period`() {
        bind()
        operatingExpenseRepository.save(
            OperatingExpense(description = "Energia hoje", amountCents = 1_000, category = ExpenseCategory.UTILITIES, expenseDate = LocalDate.now(zone)),
        )
        operatingExpenseRepository.save(
            OperatingExpense(description = "Energia antiga", amountCents = 5_000, category = ExpenseCategory.UTILITIES, expenseDate = LocalDate.now(zone).minusDays(10)),
        )

        val today = LocalDate.now(zone)
        val dre = dreService.compute(today, today)

        assertEquals(1_000, dre.operatingExpensesCents, "só a despesa de hoje")
        assertEquals(-1_000, dre.netProfitCents, "sem receita, lucro = -despesa")
    }

    @Test
    fun `create snapshot - marketplace only on DELIVERY, card only on card method, channel derived`() {
        val tenant = bind()
        setFees(marketplacePct = 20, cardPct = 5)

        val product = productService.create(
            ProductCreateRequest(categoryId = UUID.randomUUID(), sku = "DRE-${UUID.randomUUID().toString().take(6)}", name = "Burger", priceCents = 1_000),
        ).id

        // Operador autenticado (userId != null): DELIVERY + PIX -> marketplace 20%, card 0, canal DELIVERY.
        TenantContext.set(tenant)
        val deliveryOrder = orderService.create(
            OrderCreateRequest(orderType = OrderType.DELIVERY, paymentMethod = PaymentMethod.PIX, items = listOf(OrderItemRequest(productId = product, quantity = 1))),
            userId = UUID.randomUUID(),
        )
        // DINE_IN + CREDIT_CARD -> marketplace 0, card 5%, canal DINE_IN.
        TenantContext.set(tenant)
        val dineOrder = orderService.create(
            OrderCreateRequest(orderType = OrderType.DINE_IN, paymentMethod = PaymentMethod.CREDIT_CARD, items = listOf(OrderItemRequest(productId = product, quantity = 1))),
            userId = UUID.randomUUID(),
        )
        // Pedido público (userId == null): canal ONLINE mesmo sendo DELIVERY -> sem marketplace.
        TenantContext.set(tenant)
        val publicOrder = orderService.create(
            OrderCreateRequest(orderType = OrderType.DELIVERY, paymentMethod = PaymentMethod.PIX, items = listOf(OrderItemRequest(productId = product, quantity = 1))),
            userId = null,
        )

        TenantContext.set(tenant)
        val delivery = orderRepository.findById(deliveryOrder.id).get()
        val dine = orderRepository.findById(dineOrder.id).get()
        val pub = orderRepository.findById(publicOrder.id).get()

        assertEquals(SalesChannel.DELIVERY, delivery.salesChannel)
        assertEquals(200, delivery.marketplaceFeeCents, "1000 * 20%")
        assertEquals(0, delivery.cardFeeCents, "PIX não paga taxa de cartão")

        assertEquals(SalesChannel.DINE_IN, dine.salesChannel)
        assertEquals(0, dine.marketplaceFeeCents, "DINE_IN não é marketplace")
        assertEquals(50, dine.cardFeeCents, "1000 * 5%")

        assertEquals(SalesChannel.ONLINE, pub.salesChannel, "pedido público é canal ONLINE")
        assertEquals(0, pub.marketplaceFeeCents, "ONLINE não dispara marketplace")
    }

    @Test
    fun `create snapshot - cogs reflects the recipe cost at order time`() {
        val tenant = bind()
        val product = productService.create(
            ProductCreateRequest(categoryId = UUID.randomUUID(), sku = "DRE-CMV-${UUID.randomUUID().toString().take(6)}", name = "Burger", priceCents = 3_000),
        ).id
        // Estoque alto para a baixa via ficha técnica não barrar o pedido.
        val pao = ingredientService.create(IngredientRequest(name = "Pao", unitCostCents = 100, stockQuantity = 1000.0))
        val carne = ingredientService.create(IngredientRequest(name = "Carne", unitCostCents = 500, stockQuantity = 1000.0))
        recipeService.upsert(product, pao.id, RecipeItemRequest(quantity = 2.0))   // 2 * 100 = 200
        recipeService.upsert(product, carne.id, RecipeItemRequest(quantity = 1.0)) // 1 * 500 = 500 -> CMV 700

        TenantContext.set(tenant)
        val order = orderService.create(
            OrderCreateRequest(items = listOf(OrderItemRequest(productId = product, quantity = 2))),
            userId = null,
        )

        TenantContext.set(tenant)
        val saved = orderRepository.findById(order.id).get()
        assertEquals(1_400, saved.cogsCents, "CMV 700 por unidade x 2 = 1400")
    }
}
