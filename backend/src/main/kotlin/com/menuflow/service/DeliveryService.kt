package com.menuflow.service

import com.menuflow.dto.AssignDriverRequest
import com.menuflow.dto.DeliveryOrderResponse
import com.menuflow.dto.DeliveryStatusUpdateRequest
import com.menuflow.dto.DriverCreateRequest
import com.menuflow.dto.DriverResponse
import com.menuflow.exception.BusinessException
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.model.DeliveryDriver
import com.menuflow.model.DeliveryStatus
import com.menuflow.model.OrderType
import com.menuflow.repository.tenant.DeliveryDriverRepository
import com.menuflow.repository.tenant.OrderRepository
import com.menuflow.security.SecurityUtils
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Delivery dispatch (Sprint 2). Drivers and dispatch state live in the TENANT
 * database (already tenant-scoped by the routed datasource). Status changes are
 * broadcast to /topic/delivery/{tenantSlug}.
 */
@Service
class DeliveryService(
    private val driverRepository: DeliveryDriverRepository,
    private val orderRepository: OrderRepository,
    private val realtimePublisher: RealtimePublisher,
) {
    private val saoPaulo = ZoneId.of("America/Sao_Paulo")

    @Transactional("tenantTransactionManager")
    fun createDriver(req: DriverCreateRequest): DriverResponse {
        val tenantUuid = SecurityUtils.currentPrincipalOrThrow().tenantUuid
        val saved = driverRepository.save(
            DeliveryDriver(
                name = req.name.trim(),
                phone = req.phone.trim(),
                licensePlate = req.licensePlate?.trim()?.uppercase(),
                tenantId = tenantUuid,
            ),
        )
        return DriverResponse.from(saved)
    }

    @Transactional("tenantTransactionManager", readOnly = true)
    fun listActiveDrivers(): List<DriverResponse> =
        driverRepository.findByActiveTrueOrderByNameAsc().map { DriverResponse.from(it) }

    /**
     * Assigns a courier to a delivery order and sets deliveryStatus = ASSIGNED.
     * Guards: the order must be a DELIVERY order; the driver must exist and be
     * active. Publishes the dispatch to the tenant delivery topic.
     */
    @Transactional("tenantTransactionManager")
    fun assign(orderId: UUID, req: AssignDriverRequest): DeliveryOrderResponse {
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResourceNotFoundException("Order not found: $orderId") }
        if (order.orderType != OrderType.DELIVERY) {
            throw BusinessException("Order ${order.orderNumber} is not a delivery order")
        }
        val driver = driverRepository.findById(req.driverId)
            .orElseThrow { ResourceNotFoundException("Driver not found: ${req.driverId}") }
        if (!driver.active) {
            throw BusinessException("Driver ${driver.name} is inactive")
        }

        order.driverId = driver.id
        order.deliveryStatus = DeliveryStatus.ASSIGNED
        val saved = orderRepository.save(order)

        val payload = DeliveryOrderResponse.from(saved)
        realtimePublisher.publishDelivery(currentTenantSlug(), payload)
        return payload
    }

    /**
     * Courier updates dispatch status (ASSIGNED -> OUT_FOR_DELIVERY -> DELIVERED).
     * Order must already be assigned to a driver. Publishes to the delivery topic.
     */
    @Transactional("tenantTransactionManager")
    fun updateStatus(orderId: UUID, req: DeliveryStatusUpdateRequest): DeliveryOrderResponse {
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResourceNotFoundException("Order not found: $orderId") }
        val current = order.deliveryStatus
            ?: throw BusinessException("Order ${order.orderNumber} has not been assigned to a driver")
        validateTransition(current, req.deliveryStatus)

        order.deliveryStatus = req.deliveryStatus
        val saved = orderRepository.save(order)

        val payload = DeliveryOrderResponse.from(saved)
        realtimePublisher.publishDelivery(currentTenantSlug(), payload)
        return payload
    }

    @Transactional("tenantTransactionManager", readOnly = true)
    fun activeDeliveryOrders(): List<DeliveryOrderResponse> {
        val from = LocalDate.now(saoPaulo).atStartOfDay(saoPaulo).toInstant()
        return orderRepository.findActiveDeliveryOrders(from).map { DeliveryOrderResponse.from(it) }
    }

    private fun validateTransition(current: DeliveryStatus, next: DeliveryStatus) {
        val allowed = when (current) {
            DeliveryStatus.ASSIGNED -> setOf(DeliveryStatus.OUT_FOR_DELIVERY)
            DeliveryStatus.OUT_FOR_DELIVERY -> setOf(DeliveryStatus.DELIVERED)
            DeliveryStatus.DELIVERED -> emptySet()
        }
        if (next !in allowed) {
            throw BusinessException("Invalid delivery status transition from $current to $next")
        }
    }

    /** Tenant slug from the signed principal (authoritative), for the topic name. */
    private fun currentTenantSlug(): String = SecurityUtils.currentPrincipalOrThrow().tenantSlug
}
