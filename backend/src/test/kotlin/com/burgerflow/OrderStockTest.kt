package com.burgerflow

import com.burgerflow.dto.OrderCreateRequest
import com.burgerflow.dto.OrderItemRequest
import com.burgerflow.dto.ProductCreateRequest
import com.burgerflow.exception.UnprocessableEntityException
import com.burgerflow.model.Ingredient
import com.burgerflow.model.IngredientUnit
import com.burgerflow.model.OrderStatus
import com.burgerflow.model.ProductIngredient
import com.burgerflow.repository.tenant.IngredientRepository
import com.burgerflow.repository.tenant.ProductIngredientRepository
import com.burgerflow.service.OrderService
import com.burgerflow.service.ProductService
import com.burgerflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import java.util.UUID

class OrderStockTest @Autowired constructor(
    private val orderService: OrderService,
    private val productService: ProductService,
    private val ingredientRepository: IngredientRepository,
    private val productIngredientRepository: ProductIngredientRepository,
    private val tenantTx: TenantTestTx,
) : IntegrationTestBase() {

    // Each test uses its OWN tenant database so seeded ingredients never collide.
    private lateinit var tenant: String

    @BeforeEach
    fun bind() {
        tenant = "stock_${UUID.randomUUID().toString().take(8)}"
        TenantContext.set(tenant)
    }

    @AfterEach
    fun clear() = TenantContext.clear()

    private fun seedProductWithRecipe(stock: Double, qtyPerProduct: Double): UUID {
        TenantContext.set(tenant)
        val product = productService.create(
            ProductCreateRequest(
                categoryId = UUID.randomUUID(),
                sku = "BURGER-${UUID.randomUUID().toString().take(6)}",
                name = "Burger",
                priceCents = 3000,
            ),
        )
        // Persist ingredient + ficha técnica inside a tenant transaction.
        tenantTx.run {
            TenantContext.set(tenant)
            val patty = ingredientRepository.save(
                Ingredient(name = "Patty-${UUID.randomUUID()}", unit = IngredientUnit.GRAM, stockQuantity = stock),
            )
            productIngredientRepository.save(
                ProductIngredient(
                    productId = product.id,
                    ingredientId = patty.id!!,
                    quantity = qtyPerProduct,
                    unit = IngredientUnit.GRAM,
                ),
            )
        }
        return product.id
    }

    @Test
    fun `creating an order decrements ingredient stock via ficha tecnica`() {
        val productId = seedProductWithRecipe(stock = 1000.0, qtyPerProduct = 150.0)

        TenantContext.set(tenant)
        val order = orderService.create(
            OrderCreateRequest(items = listOf(OrderItemRequest(productId = productId, quantity = 2))),
            userId = null,
        )

        assertEquals(OrderStatus.PENDING, order.status)
        assertEquals(6000, order.totalCents) // 3000 * 2
        assertEquals(1, order.items.size)

        // 1000 - (150 * 2) = 700 remaining
        tenantTx.run {
            TenantContext.set(tenant)
            val remaining = ingredientRepository.findAll().first().stockQuantity
            assertEquals(700.0, remaining, 0.0001)
        }
    }

    @Test
    fun `insufficient stock aborts the order with 422 and writes nothing`() {
        val productId = seedProductWithRecipe(stock = 100.0, qtyPerProduct = 150.0)

        TenantContext.set(tenant)
        val ex = assertThrows(UnprocessableEntityException::class.java) {
            orderService.create(
                OrderCreateRequest(items = listOf(OrderItemRequest(productId = productId, quantity = 1))),
                userId = null,
            )
        }
        assertTrue(ex.details.isNotEmpty())
        assertEquals("Insufficient ingredient stock", ex.message)

        // No order persisted, stock untouched (transaction rolled back).
        TenantContext.set(tenant)
        val orders = orderService.list(null, null, null, PageRequest.of(0, 10))
        assertEquals(0, orders.totalElements)
        tenantTx.run {
            TenantContext.set(tenant)
            assertEquals(100.0, ingredientRepository.findAll().first().stockQuantity, 0.0001)
        }
    }
}
