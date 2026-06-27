package com.menuflow

import com.menuflow.dto.OrderCreateRequest
import com.menuflow.dto.OrderItemRequest
import com.menuflow.dto.OrderStatusUpdateRequest
import com.menuflow.dto.ProductCreateRequest
import com.menuflow.model.Order
import com.menuflow.model.OrderStatus
import com.menuflow.repository.tenant.OrderRepository
import com.menuflow.service.OrderService
import com.menuflow.service.ProductService
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * GET /kds/orders snapshot scope (3-column board: Novos/PENDING · Em preparo/PREPARING
 * · Prontos/READY). Proves the service returns PENDING + PREPARING + same-day READY,
 * and that READY from a previous day is excluded so the board is not polluted.
 *
 * Service-level (the WS push path is covered by KdsWebSocketTest); needs the routed
 * tenant datasource, so it extends IntegrationTestBase and binds a tenant per test.
 */
class KdsSnapshotTest @Autowired constructor(
    private val orderService: OrderService,
    private val productService: ProductService,
    private val orderRepository: OrderRepository,
) : IntegrationTestBase() {

    private lateinit var tenant: String

    @BeforeEach
    fun bind() {
        tenant = "kdssnap_${UUID.randomUUID().toString().take(8)}"
        TenantContext.set(tenant)
    }

    @AfterEach
    fun clear() = TenantContext.clear()

    private fun newOrderId(): UUID {
        val product = productService.create(
            ProductCreateRequest(
                UUID.randomUUID(),
                "S-${UUID.randomUUID().toString().take(6)}",
                "Burger",
                priceCents = 1000,
            ),
        )
        return orderService.create(
            OrderCreateRequest(items = listOf(OrderItemRequest(productId = product.id, quantity = 1))),
            userId = null,
        ).id
    }

    @Test
    fun `board returns PENDING, PREPARING and same-day READY`() {
        val pendingId = newOrderId()

        val preparingId = newOrderId()
        orderService.updateStatus(preparingId, OrderStatusUpdateRequest(OrderStatus.PREPARING))

        val readyId = newOrderId()
        orderService.updateStatus(readyId, OrderStatusUpdateRequest(OrderStatus.PREPARING))
        orderService.updateStatus(readyId, OrderStatusUpdateRequest(OrderStatus.READY))

        // A DELIVERED order must NOT appear (terminal, left the kitchen).
        val deliveredId = newOrderId()
        orderService.updateStatus(deliveredId, OrderStatusUpdateRequest(OrderStatus.PREPARING))
        orderService.updateStatus(deliveredId, OrderStatusUpdateRequest(OrderStatus.READY))
        orderService.updateStatus(deliveredId, OrderStatusUpdateRequest(OrderStatus.DELIVERED))

        val board = orderService.kdsActiveOrders()
        val byId = board.associateBy { it.orderId }

        assertEquals(OrderStatus.PENDING, byId[pendingId]?.status, "PENDING must be on the board")
        assertEquals(OrderStatus.PREPARING, byId[preparingId]?.status, "PREPARING must be on the board")
        assertEquals(OrderStatus.READY, byId[readyId]?.status, "same-day READY must be on the board")
        assertFalse(byId.containsKey(deliveredId), "DELIVERED must not be on the board")
    }

    @Test
    fun `READY from a previous day is excluded from the board`() {
        // Same-day READY (via the normal lifecycle) — must be present.
        val todayReadyId = newOrderId()
        orderService.updateStatus(todayReadyId, OrderStatusUpdateRequest(OrderStatus.PREPARING))
        orderService.updateStatus(todayReadyId, OrderStatusUpdateRequest(OrderStatus.READY))

        // Backdated READY order: created_at two days ago. created_at is updatable=false,
        // so it is set on INSERT and survives — we persist the entity directly with a
        // past timestamp to simulate yesterday's finished ticket.
        val twoDaysAgo = Instant.now().minus(2, ChronoUnit.DAYS)
        val stale = orderRepository.save(
            Order(
                orderNumber = "STALE-${UUID.randomUUID().toString().take(8)}",
                status = OrderStatus.READY,
                subtotalCents = 1000,
                totalCents = 1000,
                createdAt = twoDaysAgo,
                updatedAt = twoDaysAgo,
            ),
        )

        val board = orderService.kdsActiveOrders()
        val ids = board.map { it.orderId }.toSet()

        assertTrue(ids.contains(todayReadyId), "today's READY must be on the board")
        assertFalse(ids.contains(stale.id), "yesterday's READY must NOT pollute the board")
    }
}
