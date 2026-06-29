package com.menuflow.model

import jakarta.persistence.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Despesa operacional lançada manualmente (aluguel, energia, salários, etc.).
 * Vive no banco do TENANT (db-per-tenant), então não precisa de coluna de escopo.
 * Dinheiro em CENTAVOS. Entra no DRE pela [expenseDate] (regime de competência
 * simples por data lançada). NÃO é vinculada a pedido — é custo fixo do negócio.
 */
@Entity
@Table(name = "operating_expenses")
data class OperatingExpense(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "description", nullable = false, length = 200)
    var description: String,

    @Column(name = "amount_cents", nullable = false)
    var amountCents: Long,

    @Column(name = "category", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    var category: ExpenseCategory,

    @Column(name = "expense_date", nullable = false)
    var expenseDate: LocalDate,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun preUpdate() {
        updatedAt = Instant.now()
    }
}

/** Categorias de despesa operacional para o recorte do DRE. */
enum class ExpenseCategory {
    RENT,       // aluguel
    UTILITIES,  // contas (água, energia, internet)
    PAYROLL,    // folha/salários
    MARKETING,  // marketing/anúncios
    OTHER,      // outras
}
