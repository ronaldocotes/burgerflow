package com.menuflow

import com.menuflow.dto.OrderCreateRequest
import com.menuflow.dto.OrderItemRequest
import com.menuflow.dto.ProductCreateRequest
import com.menuflow.dto.ProductOptionGroupRequest
import com.menuflow.dto.ProductOptionRequest
import com.menuflow.exception.BusinessException
import com.menuflow.service.OrderService
import com.menuflow.service.ProductOptionGroupService
import com.menuflow.service.ProductService
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Liga complementos ao pedido: o preço do item soma os adicionais escolhidos, o
 * snapshot fica no pedido, e um grupo obrigatório não atendido bloqueia a criação.
 */
class OrderItemOptionsTest @Autowired constructor(
    private val orderService: OrderService,
    private val productService: ProductService,
    private val optionGroupService: ProductOptionGroupService,
) : IntegrationTestBase() {

    @AfterEach
    fun clear() = TenantContext.clear()

    private fun newProduct(price: Long): UUID {
        val p = productService.create(
            ProductCreateRequest(
                categoryId = UUID.randomUUID(),
                sku = "OPT-${UUID.randomUUID().toString().take(6)}",
                name = "Burger",
                priceCents = price,
            ),
        )
        return p.id
    }

    @Test
    fun `order item price includes chosen option add-ons and snapshots them`() {
        TenantContext.set("optorder")
        val productId = newProduct(3000)
        val group = optionGroupService.createGroup(productId, ProductOptionGroupRequest("Adicionais", 0, 3))
        val bacon = optionGroupService.addOption(productId, group.id, ProductOptionRequest("Bacon", 300))

        val order = orderService.create(
            OrderCreateRequest(
                items = listOf(OrderItemRequest(productId = productId, quantity = 1, optionIds = listOf(bacon.id))),
            ),
            null,
        )

        assertEquals(3300, order.items[0].unitPriceCents, "unit price = base 3000 + bacon 300")
        assertEquals(3300, order.totalCents)
        assertEquals(1, order.items[0].options.size)
        assertEquals("Bacon", order.items[0].options[0].optionName)
        assertEquals(300, order.items[0].options[0].priceCents)
    }

    @Test
    fun `required option group blocks order when not chosen`() {
        TenantContext.set("optorder2")
        val productId = newProduct(3000)
        val group = optionGroupService.createGroup(productId, ProductOptionGroupRequest("Ponto da carne", 1, 1))
        optionGroupService.addOption(productId, group.id, ProductOptionRequest("Mal passado", 0))

        assertThrows<BusinessException> {
            orderService.create(
                OrderCreateRequest(items = listOf(OrderItemRequest(productId = productId, quantity = 1))),
                null,
            )
        }
    }
}
