package com.menuflow

import com.menuflow.dto.OrderCreateRequest
import com.menuflow.dto.OrderItemRequest
import com.menuflow.dto.OrderStatusUpdateRequest
import com.menuflow.dto.ProductCreateRequest
import com.menuflow.exception.BusinessException
import com.menuflow.model.OrderStatus
import com.menuflow.service.OrderService
import com.menuflow.service.ProductService
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

class OrderStatusTransitionTest @Autowired constructor(
    private val orderService: OrderService,
    private val productService: ProductService,
) : IntegrationTestBase() {

    private lateinit var tenant: String

    @BeforeEach
    fun bind() {
        tenant = "status_${UUID.randomUUID().toString().take(8)}"
        TenantContext.set(tenant)
    }

    @AfterEach
    fun clear() = TenantContext.clear()

    private fun newOrderId(): UUID {
        val product = productService.create(
            ProductCreateRequest(UUID.randomUUID(), "S-${UUID.randomUUID().toString().take(6)}", "Burger", priceCents = 1000),
        )
        return orderService.create(
            OrderCreateRequest(items = listOf(OrderItemRequest(productId = product.id, quantity = 1))),
            userId = null,
        ).id
    }

    @Test
    fun `valid lifecycle PENDING to PREPARING to READY to DELIVERED`() {
        val id = newOrderId()
        assertEquals(OrderStatus.PREPARING, orderService.updateStatus(id, OrderStatusUpdateRequest(OrderStatus.PREPARING)).status)
        assertEquals(OrderStatus.READY, orderService.updateStatus(id, OrderStatusUpdateRequest(OrderStatus.READY)).status)
        val delivered = orderService.updateStatus(id, OrderStatusUpdateRequest(OrderStatus.DELIVERED))
        assertEquals(OrderStatus.DELIVERED, delivered.status)
        assertNotNull(delivered.completedAt)
    }

    @Test
    fun `illegal jump PENDING to DELIVERED is rejected`() {
        val id = newOrderId()
        assertThrows(BusinessException::class.java) {
            orderService.updateStatus(id, OrderStatusUpdateRequest(OrderStatus.DELIVERED))
        }
    }

    @Test
    fun `cannot transition out of terminal CANCELLED`() {
        val id = newOrderId()
        orderService.updateStatus(id, OrderStatusUpdateRequest(OrderStatus.CANCELLED, "test"))
        assertThrows(BusinessException::class.java) {
            orderService.updateStatus(id, OrderStatusUpdateRequest(OrderStatus.PREPARING))
        }
    }
}
