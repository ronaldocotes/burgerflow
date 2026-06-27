package com.menuflow

import com.menuflow.dto.OrderCreateRequest
import com.menuflow.dto.OrderItemRequest
import com.menuflow.dto.ProductCreateRequest
import com.menuflow.dto.ProductCrustPriceRequest
import com.menuflow.dto.ProductFlavorRequest
import com.menuflow.dto.ProductOptionGroupRequest
import com.menuflow.dto.ProductOptionRequest
import com.menuflow.dto.ProductSizeRequest
import com.menuflow.dto.QuoteRequest
import com.menuflow.exception.BusinessException
import com.menuflow.model.OrderType
import com.menuflow.service.OrderService
import com.menuflow.service.ProductCrustPriceService
import com.menuflow.service.ProductFlavorService
import com.menuflow.service.ProductOptionGroupService
import com.menuflow.service.ProductService
import com.menuflow.service.ProductSizeService
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID

/**
 * POST /orders/quote: cota o carrinho SEM criar o pedido. Prova que (a) o total
 * cotado é correto para um carrinho misto (lanche + complemento e pizza meia/meia
 * com tamanho/borda/promo), (b) o total do quote é IGUAL ao do pedido criado com
 * os mesmos itens (consistência quote<->create), e (c) um grupo obrigatório não
 * atendido falha no quote como falharia no create.
 */
class OrderQuoteTest @Autowired constructor(
    private val orderService: OrderService,
    private val productService: ProductService,
    private val sizeService: ProductSizeService,
    private val flavorService: ProductFlavorService,
    private val crustService: ProductCrustPriceService,
    private val optionGroupService: ProductOptionGroupService,
) : IntegrationTestBase() {

    @AfterEach
    fun clear() = TenantContext.clear()

    private val yesterday = Instant.now().minusSeconds(86_400)
    private val tomorrow = Instant.now().plusSeconds(86_400)
    private fun sku(p: String) = "$p-${UUID.randomUUID().toString().take(5)}"

    @Test
    fun `quote of a mixed cart returns correct subtotal and total`() {
        TenantContext.set("quote1")

        // Lanche com complemento: base 3000 + bacon 300 = 3300.
        val burger = productService.create(
            ProductCreateRequest(categoryId = UUID.randomUUID(), sku = sku("BURG"), name = "Burger", priceCents = 3000),
        ).id
        val group = optionGroupService.createGroup(burger, ProductOptionGroupRequest("Adicionais", 0, 3))
        val bacon = optionGroupService.addOption(burger, group.id, ProductOptionRequest("Bacon", 300))

        // Pizza meia/meia com tamanho em promo + borda. Janela de promo ativa.
        val pizza = productService.create(
            ProductCreateRequest(
                categoryId = UUID.randomUUID(), sku = sku("PZ"), name = "Pizza",
                priceCents = 0, promoStartsAt = yesterday, promoEndsAt = tomorrow,
            ),
        ).id
        val grande = sizeService.create(pizza, ProductSizeRequest("Grande", "G", 5000, promoPriceCents = 4000))
        val calabresa = flavorService.create(pizza, ProductFlavorRequest("Calabresa", priceCents = 1000))
        val portuguesa = flavorService.create(pizza, ProductFlavorRequest("Portuguesa", priceCents = 1500))
        crustService.upsert(pizza, ProductCrustPriceRequest(crustType = "CATUPIRY", priceCents = 800))

        val quote = orderService.quote(
            QuoteRequest(
                orderType = OrderType.DELIVERY,
                deliveryFeeCents = 500,
                discountCents = 350,
                items = listOf(
                    OrderItemRequest(productId = burger, quantity = 1, optionIds = listOf(bacon.id)),
                    OrderItemRequest(
                        productId = pizza, quantity = 1, sizeId = grande.id,
                        flavor1Id = calabresa.id, flavor2Id = portuguesa.id, crustType = "CATUPIRY", doughType = "FINA",
                    ),
                ),
            ),
        )

        // Lanche: 3300. Pizza: tamanho promo 4000 + média(1000,1500)=1250 + borda 800 = 6050.
        assertEquals(3300, quote.items[0].unitPriceCents)
        assertEquals(6050, quote.items[1].unitPriceCents)
        // subtotal 9350; total = 9350 - 350 (desconto) + 500 (entrega) = 9500.
        assertEquals(9350, quote.subtotalCents)
        assertEquals(350, quote.discountCents)
        assertEquals(500, quote.deliveryFeeCents)
        assertEquals(9500, quote.totalCents)
        // Snapshot da variação/complementos exposto no quote.
        assertEquals("Bacon", quote.items[0].options[0].optionName)
        assertEquals("Calabresa", quote.items[1].flavor1Name)
        assertEquals("Portuguesa", quote.items[1].flavor2Name)
        assertEquals("CATUPIRY", quote.items[1].crustType)
        assertEquals("FINA", quote.items[1].doughType)
    }

    @Test
    fun `quote total equals the created order total for the same items`() {
        TenantContext.set("quote2")
        val pizza = productService.create(
            ProductCreateRequest(categoryId = UUID.randomUUID(), sku = sku("PZ"), name = "Pizza", priceCents = 0),
        ).id
        val media = sizeService.create(pizza, ProductSizeRequest("Media", "M", 4000))
        val a = flavorService.create(pizza, ProductFlavorRequest("A", priceCents = 300))
        val b = flavorService.create(pizza, ProductFlavorRequest("B", priceCents = 301))

        val items = listOf(
            OrderItemRequest(productId = pizza, quantity = 2, sizeId = media.id, flavor1Id = a.id, flavor2Id = b.id),
        )

        val quote = orderService.quote(
            QuoteRequest(orderType = OrderType.DELIVERY, deliveryFeeCents = 700, discountCents = 200, items = items),
        )
        val order = orderService.create(
            OrderCreateRequest(orderType = OrderType.DELIVERY, deliveryFeeCents = 700, discountCents = 200, items = items),
            null,
        )

        assertEquals(order.subtotalCents, quote.subtotalCents, "subtotal do quote bate com o do pedido")
        assertEquals(order.deliveryFeeCents, quote.deliveryFeeCents)
        assertEquals(order.totalCents, quote.totalCents, "total do quote bate com o cobrado no pedido")
        assertEquals(order.items[0].unitPriceCents, quote.items[0].unitPriceCents)
    }

    @Test
    fun `required option group not satisfied is rejected in quote`() {
        TenantContext.set("quote3")
        val burger = productService.create(
            ProductCreateRequest(categoryId = UUID.randomUUID(), sku = sku("BURG"), name = "Burger", priceCents = 3000),
        ).id
        val group = optionGroupService.createGroup(burger, ProductOptionGroupRequest("Ponto da carne", 1, 1))
        optionGroupService.addOption(burger, group.id, ProductOptionRequest("Mal passado", 0))

        assertThrows<BusinessException> {
            orderService.quote(
                QuoteRequest(items = listOf(OrderItemRequest(productId = burger, quantity = 1))),
            )
        }
    }
}
