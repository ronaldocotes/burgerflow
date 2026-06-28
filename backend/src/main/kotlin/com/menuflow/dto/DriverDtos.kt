package com.menuflow.dto

import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * DTOs do acerto financeiro de entregadores (Fase 2.5). Dinheiro SEMPRE em
 * centavos. [driverId] referencia delivery_drivers.id (o entregador do tenant),
 * NAO um usuario do banco de controle.
 */

// --- Entregadores ---

/**
 * Resumo do entregador para o frontend montar as URLs do acerto. [id] e o
 * DeliveryDriver.id (= orders.driver_id), o MESMO driverId usado em
 * /drivers/{driverId}/config e nos acertos — NAO o id do usuario de controle.
 */
data class DeliveryDriverResponse(
    val id: UUID,
    val name: String,
    val phone: String?,
    val isActive: Boolean,
)

// --- Configuracao de remuneracao ---

data class DriverConfigRequest(
    @field:PositiveOrZero val dailyRateCents: Long,
    @field:PositiveOrZero val perDeliveryCents: Long,
    @field:PositiveOrZero val perKmCents: Long,
    @field:Size(max = 500) val notes: String? = null,
)

data class DriverConfigResponse(
    val driverId: UUID,
    val dailyRateCents: Long,
    val perDeliveryCents: Long,
    val perKmCents: Long,
    val notes: String?,
)

// --- Acertos ---

data class OpenSettlementRequest(
    val driverId: UUID,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    @field:Size(max = 500) val notes: String? = null,
)

data class CloseSettlementRequest(
    @field:PositiveOrZero val workingDays: Int,
    @field:PositiveOrZero val kmTotalCents: Long = 0, // km e opcional (pode nao ter GPS)
    @field:Size(max = 500) val notes: String? = null,
)

data class DriverSettlementResponse(
    val id: UUID,
    val driverId: UUID,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val deliveriesCount: Int,
    val workingDays: Int,
    val dailyTotalCents: Long,
    val deliveryTotalCents: Long,
    val kmTotalCents: Long,
    val grossTotalCents: Long,
    val status: String,
    val closedAt: Instant?,
    val notes: String?,
)
