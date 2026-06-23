package com.burgerflow

import com.burgerflow.dto.AssignDriverRequest
import com.burgerflow.dto.DeliveryStatusUpdateRequest
import com.burgerflow.dto.DriverCreateRequest
import com.burgerflow.dto.OrderCreateRequest
import com.burgerflow.dto.OrderItemRequest
import com.burgerflow.dto.ProductCreateRequest
import com.burgerflow.exception.BusinessException
import com.burgerflow.model.DeliveryStatus
import com.burgerflow.model.OrderType
import com.burgerflow.security.AuthPrincipal
import com.burgerflow.service.DeliveryService
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

/**
 * Delivery dispatch (Sprint 2) integration tests. createDriver reads the tenant
 * UUID from the authenticated principal, so we seed a SecurityContext; the routed
 * datasource is bound via TenantContext as usual.
 */
class DeliveryServiceTest @Autowired constructor(
    private val deliveryService: DeliveryService,
    private val orderService: OrderService,
    private val productService: ProductService,
) : IntegrationTestBase() {

    private lateinit var tenant: String
    private val tenantUuid = UUID.randomUUID()

    @BeforeEach
    fun bind() {
        tenant = "deliv_${UUID.randomUUID().toString().take(8)}"
        TenantContext.set(tenant)
        val principal = AuthPrincipal(
            userId = UUID.randomUUID(),
            tenantSlug = tenant,
            tenantUuid = tenantUuid,
            roles = listOf("ADMIN"),
        )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, listOf(SimpleGrantedAuthority("ROLE_ADMIN")))
    }

    @AfterEach
    fun clear() {
        TenantContext.clear()
        SecurityContextHolder.clearContext()
    }

    private fun newDeliveryOrderId(): UUID {
        TenantContext.set(tenant)
        val product = productService.create(
            ProductCreateRequest(
                categoryId = UUID.randomUUID(),
                sku = "D-${UUID.randomUUID().toString().take(6)}",
                name = "Burger",
                priceCents = 2000,
            ),
        )
        return orderService.create(
            OrderCreateRequest(
                orderType = OrderType.DELIVERY,
                items = listOf(OrderItemRequest(productId = product.id, quantity = 1)),
                deliveryFeeCents = 500,
            ),
            userId = null,
        ).id
    }

    @Test
    fun `assign driver sets driverId and ASSIGNED, status flows to DELIVERED`() {
        val driver = deliveryService.createDriver(
            DriverCreateRequest(name = "Zé Moto", phone = "5599999999", licensePlate = "abc1d23"),
        )
        assertTrue(driver.active)
        assertEquals("ABC1D23", driver.licensePlate) // uppercased

        val orderId = newDeliveryOrderId()
        val assigned = deliveryService.assign(orderId, AssignDriverRequest(driverId = driver.id))
        assertEquals(driver.id, assigned.driverId)
        assertEquals(DeliveryStatus.ASSIGNED, assigned.deliveryStatus)

        // Active delivery queue shows the assigned order.
        TenantContext.set(tenant)
        assertEquals(1, deliveryService.activeDeliveryOrders().size)

        val out = deliveryService.updateStatus(orderId, DeliveryStatusUpdateRequest(DeliveryStatus.OUT_FOR_DELIVERY))
        assertEquals(DeliveryStatus.OUT_FOR_DELIVERY, out.deliveryStatus)
        val done = deliveryService.updateStatus(orderId, DeliveryStatusUpdateRequest(DeliveryStatus.DELIVERED))
        assertEquals(DeliveryStatus.DELIVERED, done.deliveryStatus)

        // DELIVERED is terminal -> dropped from the active queue.
        TenantContext.set(tenant)
        assertEquals(0, deliveryService.activeDeliveryOrders().size)
    }

    @Test
    fun `assigning a non-delivery order is rejected`() {
        val driver = deliveryService.createDriver(DriverCreateRequest("Ana", "5598888888"))
        TenantContext.set(tenant)
        val product = productService.create(
            ProductCreateRequest(UUID.randomUUID(), "DI-${UUID.randomUUID().toString().take(6)}", "Burger", priceCents = 1500),
        )
        val dineIn = orderService.create(
            OrderCreateRequest(orderType = OrderType.DINE_IN, items = listOf(OrderItemRequest(product.id, 1))),
            userId = null,
        ).id
        assertThrows(BusinessException::class.java) {
            deliveryService.assign(dineIn, AssignDriverRequest(driver.id))
        }
    }

    @Test
    fun `illegal delivery status jump is rejected`() {
        val driver = deliveryService.createDriver(DriverCreateRequest("Bia", "5597777777"))
        val orderId = newDeliveryOrderId()
        deliveryService.assign(orderId, AssignDriverRequest(driver.id))
        // ASSIGNED -> DELIVERED (skipping OUT_FOR_DELIVERY) is invalid.
        assertThrows(BusinessException::class.java) {
            deliveryService.updateStatus(orderId, DeliveryStatusUpdateRequest(DeliveryStatus.DELIVERED))
        }
    }
}
