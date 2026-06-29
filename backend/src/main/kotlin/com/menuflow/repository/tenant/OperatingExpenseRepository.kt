package com.menuflow.repository.tenant

import com.menuflow.model.OperatingExpense
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

@Repository
interface OperatingExpenseRepository : JpaRepository<OperatingExpense, UUID> {

    /** Lista paginada das despesas do período (mais recentes primeiro). */
    fun findByExpenseDateBetweenOrderByExpenseDateDesc(
        start: LocalDate,
        end: LocalDate,
        pageable: Pageable,
    ): Page<OperatingExpense>

    /**
     * Soma (centavos) das despesas operacionais lançadas no período [start, end]
     * (ambos inclusivos — expense_date é DATE). COALESCE garante 0 quando não há
     * nenhuma despesa, evitando NULL/NPE no DRE de um período vazio.
     */
    @Query(
        """
        SELECT COALESCE(SUM(e.amountCents), 0) FROM OperatingExpense e
        WHERE e.expenseDate >= :start AND e.expenseDate <= :end
        """,
    )
    fun sumInPeriod(@Param("start") start: LocalDate, @Param("end") end: LocalDate): Long
}
