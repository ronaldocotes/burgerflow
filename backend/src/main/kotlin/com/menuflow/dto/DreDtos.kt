package com.menuflow.dto

import com.menuflow.model.ExpenseCategory
import com.menuflow.model.OperatingExpense
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * DTOs do DRE Automático (Fase 3.1). Dinheiro SEMPRE em centavos (Long). As
 * margens são percentuais (Double, ex.: 62.5 = 62,5%) — não são dinheiro, então
 * não vão em centavos; arredondadas a 2 casas no serviço.
 */

/**
 * Demonstrativo de Resultado (DRE) de um período [periodStart, periodEnd]
 * (ambos inclusivos). Estrutura em cascata:
 *
 *   Receita Bruta
 *   (-) Taxas marketplace (-) Taxas cartão (-) Impostos = Receita Líquida
 *   (-) CMV                                              = Lucro Bruto
 *   (-) Despesas operacionais                            = Lucro Líquido
 *
 * Receita Bruta considera apenas pedidos DELIVERED concluídos (completedAt) no
 * período. Despesas operacionais são lançadas por expense_date.
 */
data class DreResponse(
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    // Receita
    val grossRevenueCents: Long,
    val marketplaceFeesCents: Long,
    val cardFeesCents: Long,
    val taxCents: Long,
    val netRevenueCents: Long,
    // Resultado
    val cogsCents: Long,
    val grossProfitCents: Long,
    val operatingExpensesCents: Long,
    val netProfitCents: Long,
    // Indicadores
    val orderCount: Long,
    val averageTicketCents: Long,
    val grossMarginPct: Double,
    val netMarginPct: Double,
    // Recortes (chave = nome do enum; valor = quantidade de pedidos)
    val ordersByChannel: Map<String, Long>,
    val ordersByPaymentMethod: Map<String, Long>,
)

// --- Despesas operacionais (CRUD) ---

data class OperatingExpenseRequest(
    @field:NotBlank @field:Size(max = 200) val description: String,
    @field:PositiveOrZero val amountCents: Long,
    /** Categoria do enum ExpenseCategory: RENT, UTILITIES, PAYROLL, MARKETING, OTHER. */
    val category: ExpenseCategory,
    val expenseDate: LocalDate,
)

data class OperatingExpenseResponse(
    val id: UUID,
    val description: String,
    val amountCents: Long,
    val category: ExpenseCategory,
    val expenseDate: LocalDate,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(e: OperatingExpense) = OperatingExpenseResponse(
            id = e.id!!,
            description = e.description,
            amountCents = e.amountCents,
            category = e.category,
            expenseDate = e.expenseDate,
            createdAt = e.createdAt,
            updatedAt = e.updatedAt,
        )
    }
}
