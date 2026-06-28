package com.menuflow.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Turno de caixa (CashSession) — vive no banco do TENANT (db-per-tenant: 1
 * restaurante por banco, sem coluna de escopo). Concentra a reconciliação do
 * dinheiro do balcão: abertura + vendas em dinheiro + reforços - sangrias =
 * esperado, comparado com o contado no fechamento. Dinheiro SEMPRE em centavos.
 *
 * Invariante: no máximo UMA sessão OPEN por banco (índice parcial
 * uq_cash_sessions_single_open + checagem existsByStatus no serviço).
 */
@Entity
@Table(name = "cash_sessions")
data class CashSession(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: CashSessionStatus = CashSessionStatus.OPEN,

    @Column(name = "opened_by_user_id", nullable = false)
    val openedByUserId: UUID,

    @Column(name = "opened_at", nullable = false, updatable = false)
    val openedAt: Instant = Instant.now(),

    @Column(name = "opening_amount_cents", nullable = false)
    val openingAmountCents: Long = 0,

    @Column(name = "closed_by_user_id")
    var closedByUserId: UUID? = null,

    @Column(name = "closed_at")
    var closedAt: Instant? = null,

    @Column(name = "closing_counted_cents")
    var closingCountedCents: Long? = null,

    @Column(name = "closing_expected_cents")
    var closingExpectedCents: Long? = null,

    // Coluna gerada pelo banco (contado - esperado). Read-only para o ORM: não
    // entra em INSERT/UPDATE. O serviço calcula a diferença em memória para a
    // resposta (contado - esperado), então este campo fica como a fonte de
    // verdade SQL e não é lido de volta na mesma transação.
    @Column(name = "difference_cents", insertable = false, updatable = false)
    val differenceCents: Long? = null,

    @Column
    var notes: String? = null,

    @Version
    @Column(nullable = false)
    var version: Long = 0,

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

enum class CashSessionStatus {
    OPEN,
    CLOSED,
}

enum class CashEntryType {
    /** Sangria: retira dinheiro do caixa (reduz o esperado). */
    WITHDRAWAL,

    /** Reforço: adiciona dinheiro ao caixa (aumenta o esperado). */
    DEPOSIT,
}
