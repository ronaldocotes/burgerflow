package com.menuflow.dto

import com.menuflow.model.DeliveryDriver
import com.menuflow.model.DeliveryOffer
import com.menuflow.model.DeliveryOfferStatus
import com.menuflow.model.DeliveryStatus
import com.menuflow.model.Order
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.util.UUID

data class DriverCreateRequest(
    @field:NotBlank val name: String,
    @field:NotBlank val phone: String,
    val licensePlate: String? = null,
)

data class DriverResponse(
    val id: UUID,
    val name: String,
    val phone: String,
    val licensePlate: String?,
    val active: Boolean,
    val activeShift: Boolean,
    val lastLat: Double?,
    val lastLng: Double?,
    val lastLocationAt: Instant?,
    val batteryPct: Int?,
) {
    companion object {
        fun from(d: DeliveryDriver) = DriverResponse(
            id = d.id!!,
            name = d.name,
            phone = d.phone,
            licensePlate = d.licensePlate,
            active = d.active,
            activeShift = d.activeShift,
            lastLat = d.lastLat,
            lastLng = d.lastLng,
            lastLocationAt = d.lastLocationAt,
            batteryPct = d.batteryPct,
        )
    }
}

/** Fase 6.1 — motoboy liga/desliga o turno. Estado explícito (idempotente p/ retry). */
data class ShiftRequest(
    @field:NotNull val activeShift: Boolean,
)

/** Fase 6.1 — envio de GPS pelo app do motoboy. lat/lng obrigatórios; bateria opcional. */
data class LocationUpdateRequest(
    @field:NotNull @field:DecimalMin("-90.0") @field:DecimalMax("90.0")
    val lat: Double,
    @field:NotNull @field:DecimalMin("-180.0") @field:DecimalMax("180.0")
    val lng: Double,
    @field:Min(0) @field:Max(100)
    val batteryPct: Int? = null,
)

/** Fase 6.1 — payload STOMP da posição ao vivo do entregador. */
data class DriverLocationEvent(
    val driverId: UUID,
    val lat: Double,
    val lng: Double,
    val batteryPct: Int?,
    val at: Instant,
)

/** Fase 6.1 — oferta de entrega (resposta REST + payload STOMP para o app do motoboy). */
data class DeliveryOfferResponse(
    val id: UUID,
    val orderId: UUID,
    val driverId: UUID,
    val status: DeliveryOfferStatus,
    val feeCents: Long,
    val distanceKm: Double?,
    val offeredAt: Instant,
    val expiresAt: Instant,
) {
    companion object {
        fun from(o: DeliveryOffer) = DeliveryOfferResponse(
            id = o.id!!,
            orderId = o.orderId,
            driverId = o.driverId,
            status = o.status,
            feeCents = o.feeCents,
            distanceKm = o.distanceKm,
            offeredAt = o.offeredAt,
            expiresAt = o.expiresAt,
        )
    }
}

data class AssignDriverRequest(
    val driverId: UUID,
)

data class DeliveryStatusUpdateRequest(
    val deliveryStatus: DeliveryStatus,
)

/** Response + STOMP payload for a delivery dispatch. */
data class DeliveryOrderResponse(
    val orderId: UUID,
    val orderNumber: String,
    val driverId: UUID?,
    val deliveryStatus: DeliveryStatus?,
    val totalCents: Long,
    val tableNumber: String?,
    val updatedAt: Instant,
) {
    companion object {
        fun from(o: Order) = DeliveryOrderResponse(
            orderId = o.id!!,
            orderNumber = o.orderNumber,
            driverId = o.driverId,
            deliveryStatus = o.deliveryStatus,
            totalCents = o.totalCents,
            tableNumber = o.tableNumber,
            updatedAt = o.updatedAt,
        )
    }
}
