package com.menuflow.dto

import com.menuflow.model.DeliveryDriver
import com.menuflow.model.DeliveryStatus
import com.menuflow.model.Order
import jakarta.validation.constraints.NotBlank
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
) {
    companion object {
        fun from(d: DeliveryDriver) = DriverResponse(
            id = d.id!!,
            name = d.name,
            phone = d.phone,
            licensePlate = d.licensePlate,
            active = d.active,
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
