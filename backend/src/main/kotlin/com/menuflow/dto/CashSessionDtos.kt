package com.menuflow.dto

import com.menuflow.model.CashEntryType
import com.menuflow.model.CashSessionStatus
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/** Teto de sanidade para valores monetários informados pelo cliente: R$ 1 bilhão
 *  (em centavos). Barra digitação absurda / overflow sem limitar uso real. */
const val CASH_MAX_CENTS: Long = 100_000_000_000L

/**
 * Abertura do caixa: valor de troco inicial na gaveta (centavos, >= 0).
 *
 * Anti-padrão do benchmark (ClickEscale): abrir caixa com R$ 0 sem qualquer
 * validação/confirmação. Aqui a abertura com 0 é REJEITADA (422) a menos que o
 * cliente envie [confirmZeroOpening] = true — confirmação explícita de que o
 * turno inicia sem troco. Não é hard-reject porque há caso legítimo (começar
 * sem fundo de troco); é uma trava contra o "enter sem querer".
 */
data class OpenSessionRequest(
    @field:PositiveOrZero @field:Max(CASH_MAX_CENTS) val openingAmountCents: Long = 0,
    val confirmZeroOpening: Boolean = false,
    @field:Size(max = 500) val notes: String? = null,
)

/** Sangria (WITHDRAWAL) ou reforço (DEPOSIT). Valor positivo; motivo obrigatório. */
data class EntryRequest(
    val type: CashEntryType,
    @field:Positive @field:Max(CASH_MAX_CENTS) val amountCents: Long,
    @field:NotBlank @field:Size(max = 255) val reason: String,
)

/**
 * Fechamento do caixa com reconciliação por forma de pagamento.
 *
 * [countedAmountCents] é o DINHEIRO contado na gaveta (obrigatório). [countedCardCents]
 * e [countedPixCents] são o que o operador confere no comprovante da maquininha e no
 * extrato/PIX — opcionais: quando ausentes, a diferença daquela forma vem nula (não é
 * marcada como sobra/falta indevida). [withdrawnAmountCents] é o dinheiro retirado da
 * gaveta NO fechamento (vai para o cofre/banco); não afeta o esperado do turno, só o
 * saldo sugerido para a próxima abertura (contado - retirado).
 */
data class CloseSessionRequest(
    @field:PositiveOrZero @field:Max(CASH_MAX_CENTS) val countedAmountCents: Long,
    @field:PositiveOrZero @field:Max(CASH_MAX_CENTS) val countedCardCents: Long? = null,
    @field:PositiveOrZero @field:Max(CASH_MAX_CENTS) val countedPixCents: Long? = null,
    @field:PositiveOrZero @field:Max(CASH_MAX_CENTS) val withdrawnAmountCents: Long? = null,
    @field:Size(max = 1000) val notes: String? = null,
)

data class CashEntryResponse(
    val id: UUID,
    val type: CashEntryType,
    val amountCents: Long,
    val reason: String?,
    val createdByUserId: UUID,
    val createdAt: Instant,
)

/** Forma canônica da reconciliação de fechamento (dinheiro | cartão | pix). */
enum class ReconciliationMethod {
    CASH,
    CARD,
    PIX,
    OTHER,
}

/**
 * Uma linha da tabela de reconciliação: Esperado (sistema) | Em caixa (contado) |
 * Diferença. [countedCents]/[differenceCents] são nulos enquanto o turno está
 * aberto (preview) ou quando o operador não informou o contado daquela forma.
 * Diferença negativa = falta; positiva = sobra.
 */
data class PaymentMethodReconciliation(
    val method: ReconciliationMethod,
    val expectedCents: Long,
    val countedCents: Long?,
    val differenceCents: Long?,
)

/**
 * Estado do turno de caixa com o detalhamento do esperado do DINHEIRO:
 * esperado = abertura + vendas em dinheiro + reforços - sangrias.
 * [differenceCents] (contado - esperado, do dinheiro) só existe após o fechamento.
 * [reconciliation] traz Esperado|Em caixa|Diferença por forma (dinheiro/cartão/pix)
 * — preview enquanto aberto, snapshot após fechado.
 * [suggestedNextOpeningCents] = dinheiro contado - retirado no fechamento.
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
    val reconciliation: List<PaymentMethodReconciliation>,
    val withdrawnAtCloseCents: Long?,
    val suggestedNextOpeningCents: Long?,
    val entries: List<CashEntryResponse>,
    val notes: String?,
)
