package com.menuflow.repository.tenant

import com.menuflow.model.LoyaltyTransaction
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * Extrato APPEND-ONLY de pontos de fidelidade (banco do TENANT). Só INSERT/SELECT —
 * nunca update/delete.
 */
@Repository
interface LoyaltyTransactionRepository : JpaRepository<LoyaltyTransaction, UUID> {

    /**
     * Já houve crédito ORDER_PAID para este pedido? Guarda de idempotência do
     * listener (não creditar pontos duas vezes para o mesmo pedido pago).
     */
    fun existsByOrderIdAndReason(orderId: UUID, reason: String): Boolean

    /** Últimas 10 movimentações do cliente (extrato exibido no app/painel). */
    fun findTop10ByCustomerIdOrderByCreatedAtDesc(customerId: UUID): List<LoyaltyTransaction>

    /** Total de pontos creditados (deltas positivos) — tool get_loyalty_stats. */
    @Query("SELECT COALESCE(SUM(t.pointsDelta), 0) FROM LoyaltyTransaction t WHERE t.pointsDelta > 0")
    fun sumPointsCredited(): Long

    /**
     * Soma dos deltas positivos (créditos) com createdAt no período [from, to).
     * Usado pelo sumário gerencial de fidelidade.
     */
    @Query(
        "SELECT COALESCE(SUM(t.pointsDelta), 0) FROM LoyaltyTransaction t " +
            "WHERE t.pointsDelta > 0 AND t.createdAt >= :from AND t.createdAt < :to",
    )
    fun sumPointsIssuedInPeriod(@Param("from") from: Instant, @Param("to") to: Instant): Long

    /**
     * Soma absoluta dos débitos por [reason] com createdAt no período [from, to).
     * Ex.: reason=REWARD_REDEEMED devolve total de pontos "gastos" em resgates.
     */
    @Query(
        "SELECT COALESCE(SUM(ABS(t.pointsDelta)), 0) FROM LoyaltyTransaction t " +
            "WHERE t.reason = :reason AND t.createdAt >= :from AND t.createdAt < :to",
    )
    fun sumAbsDeltaByReasonInPeriod(
        @Param("reason") reason: String,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): Long
}
