package com.menuflow

import com.menuflow.dto.OrderCreateRequest
import com.menuflow.dto.OrderItemRequest
import com.menuflow.dto.OrderStatusUpdateRequest
import com.menuflow.dto.ProductCreateRequest
import com.menuflow.exception.BusinessException
import com.menuflow.model.OrderStatus
import com.menuflow.repository.tenant.AuditLogRepository
import com.menuflow.security.AuthPrincipal
import com.menuflow.service.OrderService
import com.menuflow.service.ProductService
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

class OrderStatusTransitionTest @Autowired constructor(
    private val orderService: OrderService,
    private val productService: ProductService,
    private val auditLogRepository: AuditLogRepository,
) : IntegrationTestBase() {

    private lateinit var tenant: String
    private val tenantUuid = UUID.randomUUID()

    @BeforeEach
    fun bind() {
        tenant = "status_${UUID.randomUUID().toString().take(8)}"
        TenantContext.set(tenant)
        // Ator autenticado (JWT/principal) — auditLogService.log resolve o actorUserId
        // daqui; sem SecurityContext ele NÃO audita (ver AuditLogService.kt:51).
        val principal = AuthPrincipal(UUID.randomUUID(), tenant, tenantUuid, listOf("ADMIN"))
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, listOf(SimpleGrantedAuthority("ROLE_ADMIN")))
    }

    @AfterEach
    fun clear() {
        TenantContext.clear()
        SecurityContextHolder.clearContext()
    }

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

    @Test
    fun `advancing PENDING to PREPARING writes exactly one audit row with the previous status`() {
        val id = newOrderId()
        orderService.updateStatus(id, OrderStatusUpdateRequest(OrderStatus.PREPARING))

        val logs = auditLogRepository.findAllByEntityAndEntityId("order", id, PageRequest.of(0, 10)).content
        assertEquals(1, logs.size)
        val log = logs.single()
        assertEquals("order.status_change", log.action)
        assertNotNull(log.beforeJson)
        assertNotNull(log.afterJson)
        assertTrue(log.beforeJson!!.contains(OrderStatus.PENDING.name))
        assertTrue(log.afterJson!!.contains(OrderStatus.PREPARING.name))
    }

    @Test
    fun `cancellation is still audited as order-cancel without regression`() {
        val id = newOrderId()
        orderService.updateStatus(id, OrderStatusUpdateRequest(OrderStatus.CANCELLED, "cliente desistiu"))

        val logs = auditLogRepository.findAllByEntityAndEntityId("order", id, PageRequest.of(0, 10)).content
        assertEquals(1, logs.size)
        val log = logs.single()
        assertEquals("order.cancel", log.action)
        assertNotNull(log.beforeJson)
        assertTrue(log.beforeJson!!.contains(OrderStatus.PENDING.name))
        assertEquals("cliente desistiu", log.reason)
    }

    @Test
    fun `full lifecycle writes one audit row per transition (batch advance leaves individual trail)`() {
        val id = newOrderId()
        orderService.updateStatus(id, OrderStatusUpdateRequest(OrderStatus.PREPARING))
        orderService.updateStatus(id, OrderStatusUpdateRequest(OrderStatus.READY))
        orderService.updateStatus(id, OrderStatusUpdateRequest(OrderStatus.DELIVERED))

        val logs = auditLogRepository.findAllByEntityAndEntityId("order", id, PageRequest.of(0, 10)).content
        assertEquals(3, logs.size)
        assertTrue(logs.all { it.action == "order.status_change" })
    }
}
