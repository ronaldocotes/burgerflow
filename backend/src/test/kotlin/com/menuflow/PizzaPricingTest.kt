package com.menuflow

import com.menuflow.dto.OrderCreateRequest
import com.menuflow.dto.OrderItemRequest
import com.menuflow.dto.ProductCreateRequest
import com.menuflow.dto.ProductCrustPriceRequest
import com.menuflow.dto.ProductFlavorRequest
import com.menuflow.dto.ProductSizeRequest
import com.menuflow.exception.BusinessException
import com.menuflow.service.OrderService
import com.menuflow.service.ProductCrustPriceService
import com.menuflow.service.ProductFlavorService
import com.menuflow.service.ProductService
import com.menuflow.service.ProductSizeService
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Precificação da pizza no pedido: preço unitário = preço do TAMANHO + MÉDIA dos
 * preços dos sabores escolhidos (1 sabor = ele mesmo; 2 sabores meia/meia = média)
 * + preço da BORDA do produto. A MASSA é sem custo. Arredondamento da média é
 * HALF-UP em centavos. O snapshot da variação fica gravado no item.
 */
class PizzaPricingTest @Autowired constructor(
    private val orderService: OrderService,
    private val productService: ProductService,
    private val sizeService: ProductSizeService,
    private val flavorService: ProductFlavorService,
    private val crustService: ProductCrustPriceService,
) : IntegrationTestBase() {

    @AfterEach
    fun clear() = TenantContext.clear()

    private fun newPizza(): UUID = productService.create(
        ProductCreateRequest(
            categoryId = UUID.randomUUID(),
            sku = "PZ-${UUID.randomUUID().toString().take(6)}",
            name = "Pizza",
            priceCents = 0,
        ),
    ).id

    @Test
    fun `meia-meia price = size + average of two flavors + crust, with options, and snapshots it`() {
        TenantContext.set("pizzaprice1")
        val productId = newPizza()
        val grande = sizeService.create(productId, ProductSizeRequest("Grande", "G", 5000))
        val calabresa = flavorService.create(productId, ProductFlavorRequest("Calabresa", priceCents = 1000))
        val portuguesa = flavorService.create(productId, ProductFlavorRequest("Portuguesa", priceCents = 1500))
        crustService.upsert(productId, ProductCrustPriceRequest(crustType = "CATUPIRY", priceCents = 800))
        // grupo de complemento opcional p/ provar que complementos seguem somando
        // (preço do item = base + média sabores + borda + complemento).

        val order = orderService.create(
            OrderCreateRequest(
                items = listOf(
                    OrderItemRequest(
                        productId = productId,
                        quantity = 1,
                        sizeId = grande.id,
                        flavor1Id = calabresa.id,
                        flavor2Id = portuguesa.id,
                        crustType = "CATUPIRY",
                        doughType = "FINA",
                    ),
                ),
            ),
            null,
        )

        val item = order.items[0]
        // 5000 (tamanho) + média(1000,1500)=1250 + 800 (borda) = 7050
        assertEquals(7050, item.unitPriceCents, "tamanho + média dos sabores + borda")
        assertEquals(7050, order.totalCents)
        // snapshot da variação preenchido
        assertEquals("Grande", item.sizeName)
        assertEquals("Calabresa", item.flavor1Name)
        assertEquals("Portuguesa", item.flavor2Name)
        assertEquals("CATUPIRY", item.crustType)
        assertEquals("FINA", item.doughType)
        assertEquals(grande.id, item.sizeId)
        assertEquals(calabresa.id, item.flavor1Id)
        assertEquals(portuguesa.id, item.flavor2Id)
    }

    @Test
    fun `single flavor uses its own price over the size base`() {
        TenantContext.set("pizzaprice2")
        val productId = newPizza()
        val p = sizeService.create(productId, ProductSizeRequest("Pequena", "P", 3000))
        val mussarela = flavorService.create(productId, ProductFlavorRequest("Mussarela", priceCents = 700))

        val order = orderService.create(
            OrderCreateRequest(
                items = listOf(OrderItemRequest(productId = productId, quantity = 1, sizeId = p.id, flavor1Id = mussarela.id)),
            ),
            null,
        )

        // 3000 + 700 (1 sabor = ele mesmo) = 3700
        assertEquals(3700, order.items[0].unitPriceCents)
        assertEquals("Mussarela", order.items[0].flavor1Name)
    }

    @Test
    fun `flavor average is HALF-UP so the total closes`() {
        TenantContext.set("pizzaprice3")
        val productId = newPizza()
        val media = sizeService.create(productId, ProductSizeRequest("Media", "M", 4000))
        val a = flavorService.create(productId, ProductFlavorRequest("A", priceCents = 300))
        val b = flavorService.create(productId, ProductFlavorRequest("B", priceCents = 301))

        val order = orderService.create(
            OrderCreateRequest(
                items = listOf(OrderItemRequest(productId = productId, quantity = 2, sizeId = media.id, flavor1Id = a.id, flavor2Id = b.id)),
            ),
            null,
        )

        // média(300,301)=300.5 -> HALF-UP 301; unit = 4000 + 301 = 4301; total = 4301*2
        assertEquals(4301, order.items[0].unitPriceCents)
        assertEquals(8602, order.totalCents)
    }

    @Test
    fun `flavor from another product is rejected (anti-IDOR)`() {
        TenantContext.set("pizzaprice4")
        val pizza = newPizza()
        val other = newPizza()
        val size = sizeService.create(pizza, ProductSizeRequest("Grande", "G", 5000))
        val foreign = flavorService.create(other, ProductFlavorRequest("Intruso", priceCents = 1000))

        assertThrows<BusinessException> {
            orderService.create(
                OrderCreateRequest(
                    items = listOf(OrderItemRequest(productId = pizza, quantity = 1, sizeId = size.id, flavor1Id = foreign.id)),
                ),
                null,
            )
        }
    }
}
