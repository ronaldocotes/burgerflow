package com.menuflow.controller

import com.menuflow.dto.DeliveryTrackingResponse
import com.menuflow.model.DeliveryStatus
import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.tenant.DeliveryDriverRepository
import com.menuflow.repository.tenant.OrderRepository
import com.menuflow.repository.tenant.TenantConfigRepository
import com.menuflow.tenant.TenantContext
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

// Endpoint publico de rastreio de entrega (Fase B3).
// O cliente recebe o link via WhatsApp e acompanha o status em tempo real.
// Nao requer autenticacao: o UUID do pedido funciona como token de acesso.
// Coberto pelo permitAll path public/** do SecurityConfig.
@RestController
@RequestMapping("/public")
class PublicTrackingController(
    private val tenantRepository: TenantRepository,
    private val orderRepository: OrderRepository,
    private val driverRepository: DeliveryDriverRepository,
    private val tenantConfigRepository: TenantConfigRepository,
) {

    @GetMapping("/{tenantSlug}/rastreio/{orderId}")
    fun getTracking(
        @PathVariable tenantSlug: String,
        @PathVariable orderId: UUID,
    ): ResponseEntity<DeliveryTrackingResponse> {
        if (!tenantRepository.existsBySlug(tenantSlug)) return ResponseEntity.notFound().build()
        TenantContext.set(tenantSlug)
        return try {
            val order = orderRepository.findById(orderId).orElse(null)
                ?: return ResponseEntity.notFound().build()

            val config = tenantConfigRepository.findFirstByOrderByCreatedAtAsc()

            // Mapeia os valores do enum DeliveryStatus (Sprint 2 + Fase 6.1) para os
            // 4 estados de UI que o frontend entende.
            val uiStatus = when (order.deliveryStatus) {
                null -> "PREPARING"
                DeliveryStatus.PENDING,
                DeliveryStatus.OFFERED,
                DeliveryStatus.ASSIGNED,
                DeliveryStatus.ACCEPTED,
                DeliveryStatus.ARRIVED_AT_STORE -> "ASSIGNED"
                DeliveryStatus.PICKED_UP,
                DeliveryStatus.OUT_FOR_DELIVERY,
                DeliveryStatus.ARRIVED_AT_CUSTOMER -> "OUT_FOR_DELIVERY"
                DeliveryStatus.DELIVERED -> "DELIVERED"
                DeliveryStatus.FAILED -> "FAILED"
            }

            // Dados do entregador: expoe apenas nome (abreviado) e placa; nunca telefone.
            val showDriver = uiStatus != "PREPARING" && uiStatus != "FAILED" && order.driverId != null
            val driver = if (showDriver) {
                order.driverId?.let { driverRepository.findById(it).orElse(null) }
            } else null

            val driverName = driver?.name?.let { full ->
                val parts = full.trim().split(Regex("\\s+"))
                if (parts.size > 1) "${parts[0]} ${parts[1].first()}." else parts[0]
            }

            ResponseEntity.ok(
                DeliveryTrackingResponse(
                    status = uiStatus,
                    restaurantName = config?.restaurantName,
                    neighborhoodLabel = order.deliveryNeighborhood,
                    driverName = driverName,
                    driverLicensePlate = driver?.licensePlate,
                    updatedAt = order.updatedAt,
                ),
            )
        } finally {
            TenantContext.clear()
        }
    }
}
