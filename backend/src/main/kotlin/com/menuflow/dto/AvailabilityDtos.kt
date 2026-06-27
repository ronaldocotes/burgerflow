package com.menuflow.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import java.util.UUID

/** Janela de horário: dia da semana (1=segunda..7=domingo) e minutos desde 00:00. */
data class WindowDto(
    @field:Min(1) @field:Max(7) val dayOfWeek: Int,
    @field:Min(0) @field:Max(1439) val startMinute: Int,
    @field:Min(0) @field:Max(1439) val endMinute: Int,
)

data class AvailabilityRequest(
    /** Canais (SalesChannel): COUNTER, DINE_IN, DELIVERY, ONLINE. Vazio = todos. */
    val channels: List<String> = emptyList(),
    @field:Valid val windows: List<WindowDto> = emptyList(),
)

data class AvailabilityResponse(
    val productId: UUID,
    val channels: List<String>,
    val windows: List<WindowDto>,
)
