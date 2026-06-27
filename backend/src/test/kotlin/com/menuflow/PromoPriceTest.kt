package com.menuflow

import com.menuflow.dto.OrderCreateRequest
import com.menuflow.dto.OrderItemRequest
import com.menuflow.dto.ProductCreateRequest
import com.menuflow.dto.ProductFlavorRequest
import com.menuflow.dto.ProductSizeRequest
import com.menuflow.service.OrderService
import com.menuflow.service.ProductFlavorService
import com.menuflow.service.ProductService
import com.menuflow.service.ProductSizeService
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID

/**
 * Preço promocional: a janela [promoStartsAt, promoEndsAt] ativa a promo; o preço
 * promocional (do produto OU do tamanho de pizza) só vale dentro da janela.
 */
class PromoPriceTest @Autowired constructor(
    private val orderService: OrderService,
    private val productService: ProductService,
    private val sizeService: ProductSizeService,
    private val flavorService: ProductFlavorService,
) : IntegrationTestBase() {

    @AfterEach
    fun clear() = TenantContext.clear()

    private val yesterday = Instant.now().minusSeconds(86_400)
    private val tomorrow = Instant.now().plusSeconds(86_400)

    private fun sku(p: String) = "$p-${UUID.randomUUID().toString().take(5)}"

    @Test
    fun `active promo overrides the product base price in the order`() {
        TenantContext.set("promo1")
        val product = productService.create(
            ProductCreateRequest(
                categoryId = UUID.randomUUID(), sku = sku("BURG"), name = "Burger",
                priceCents = 1000, promoPriceCents = 700, promoStartsAt = yesterday,
            ),
        )
        assertEquals(true, product.onPromo)
        assertEquals(700, product.effectivePriceCents)

        val order = orderService.create(
            OrderCreateRequest(items = listOf(OrderItemRequest(productId = product.id, quantity = 1))),
            null,
        )
        assertEquals(700, order.items[0].unitPriceCents, "pedido usa o preço promocional ativo")
    }

    @Test
    fun `expired promo falls back to the base price`() {
        TenantContext.set("promo2")
        val product = productService.create(
            ProductCreateRequest(
                categoryId = UUID.randomUUID(), sku = sku("BURG"), name = "Burger",
                priceCents = 1000, promoPriceCents = 700, promoEndsAt = yesterday,
            ),
        )
        assertEquals(false, product.onPromo)

        val order = orderService.create(
            OrderCreateRequest(items = listOf(OrderItemRequest(productId = product.id, quantity = 1))),
            null,
        )
        assertEquals(1000, order.items[0].unitPriceCents, "promo expirada -> preço normal")
    }

    @Test
    fun `pizza size promo applies while the product promo window is active`() {
        TenantContext.set("promo3")
        // Produto pizza: só define a JANELA de promo (sem promoPriceCents no produto).
        val pizza = productService.create(
            ProductCreateRequest(
                categoryId = UUID.randomUUID(), sku = sku("PZ"), name = "Pizza",
                priceCents = 0, promoStartsAt = yesterday, promoEndsAt = tomorrow,
            ),
        )
        val grande = sizeService.create(pizza.id, ProductSizeRequest("Grande", "G", 5000, promoPriceCents = 4000))
        val mussarela = flavorService.create(pizza.id, ProductFlavorRequest("Mussarela", priceCents = 1000))

        val order = orderService.create(
            OrderCreateRequest(
                items = listOf(OrderItemRequest(productId = pizza.id, quantity = 1, sizeId = grande.id, flavor1Id = mussarela.id)),
            ),
            null,
        )
        // tamanho em promo (4000) + 1 sabor (1000) = 5000
        assertEquals(5000, order.items[0].unitPriceCents, "tamanho usa preço promocional + sabor")
    }
}
