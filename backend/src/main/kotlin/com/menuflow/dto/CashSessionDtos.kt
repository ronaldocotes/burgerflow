package com.menuflow.dto

import com.menuflow.model.CashEntryType
import com.menuflow.model.CashSessionStatus
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/** Abertura do caixa: valor de troco inicial na gaveta (centavos, >= 0). */
data class OpenSessionRequest(
    @field:PositiveOrZero val openingAmountCents: Long = 0,
    @field:Size(max = 500) val notes: String? = null,
)

/** Sangria (WITHDRAWAL) ou reforço (DEPOSIT). Valor positivo; motivo obrigatório. */
data class EntryRequest(
    val type: CashEntryType,
    @field:Positive val amountCents: Long,
    @field:NotBlank @field:Size(max = 255) val reason: String,
)

/** Fechamento: valor contado na gaveta (centavos, >= 0). */
data class CloseSessionRequest(
    @field:PositiveOrZero val countedAmountCents: Long,
    @field:Size(max = 500) val notes: String? = null,
)

data class CashEntryResponse(
    val id: UUID,
    val type: CashEntryType,
    val amountCents: Long,
    val reason: String?,
    val createdByUserId: UUID,
    val createdAt: Instant,
)

/**
 * Estado do turno de caixa com o detalhamento do esperado:
 * esperado = abertura + vendas em dinheiro + reforços - sangrias.
 * [differenceCents] (contado - esperado) só existe após o fechamento.
 */
data class CashSessionResponse(
    val id: UUID,
    val status: CashSessionStatus,
    val openedByUserId: UUID,
    val openedAt: Instant,
    val openingAmountCents: Long,
    val closedByUserId: UUID?,
    val closedAt: Instant?,
    val cashSalesCents: Long,
    val depositsCents: Long,
    val withdrawalsCents: Long,
    val expectedCents: Long,
    val countedCents: Long?,
    val differenceCents: Long?,
    val entries: List<CashEntryResponse>,
    val notes: String?,
)
