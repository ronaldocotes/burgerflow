package com.menuflow.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/** Sessão (comanda) ativa de uma mesa, exposta dentro de [TableDto]. */
data class TableSessionView(
    val sessionId: UUID,
    val status: String,
    val openedAt: Instant,
    val billRequestedAt: Instant?,
)

/**
 * Mesa do salão. [session] vem preenchida quando há comanda ativa (OPEN/BILLING);
 * na resposta de fechamento traz a sessão recém-fechada (status CLOSED) como
 * confirmação. null = mesa livre.
 */
data class TableDto(
    val id: UUID,
    val label: String,
    val seats: Int,
    val sortOrder: Int,
    val active: Boolean,
    val session: TableSessionView? = null,
)

data class TableCreateRequest(
    @field:NotBlank @field:Size(max = 40) val label: String,
    @field:Positive @field:Max(99) val seats: Int = 4,
    val sortOrder: Int = 0,
)

/** Atualização parcial: campos null não são tocados. */
data class TableUpdateRequest(
    @field:Size(max = 40) val label: String? = null,
    @field:Positive @field:Max(99) val seats: Int? = null,
    val sortOrder: Int? = null,
    val active: Boolean? = null,
)
