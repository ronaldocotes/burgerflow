package com.menuflow.repository.tenant

import com.menuflow.model.CouponRedemption
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface CouponRedemptionRepository : JpaRepository<CouponRedemption, UUID> {

    /** Total de usos do cupom (limite global maxUses). */
    fun countByCouponId(couponId: UUID): Long

    /** Usos do cupom por um telefone (limite maxUsesPerCustomer). */
    fun countByCouponIdAndCustomerPhone(couponId: UUID, customerPhone: String): Long

    /** Histórico de uso de um cupom, mais recentes primeiro. */
    fun findByCouponIdOrderByRedeemedAtDesc(couponId: UUID, pageable: Pageable): Page<CouponRedemption>

    // --- Sumário de performance (GET /coupons/summary) ---

    /** Contagem total de redenções num período. */
    @Query("SELECT COUNT(r) FROM CouponRedemption r WHERE r.redeemedAt >= :from AND r.redeemedAt < :to")
    fun countInPeriod(@Param("from") from: Instant, @Param("to") to: Instant): Long

    /**
     * Soma dos descontos concedidos num período. Retorna null quando não há redenções
     * (SUM de conjunto vazio = NULL no SQL mesmo com COALESCE em JPQL); o serviço
     * trata o null com ?: 0L.
     */
    @Query("SELECT SUM(r.discountAppliedCents) FROM CouponRedemption r WHERE r.redeemedAt >= :from AND r.redeemedAt < :to")
    fun sumDiscountInPeriod(@Param("from") from: Instant, @Param("to") to: Instant): Long?

    /**
     * Top cupons por número de redenções no período. Cada linha é
     * [couponId (UUID), contagem (Long), soma do desconto (Long)].
     * O serviço usa Pageable.ofSize(5) para limitar ao top-5.
     */
    @Query(
        """
        SELECT r.couponId, COUNT(r), COALESCE(SUM(r.discountAppliedCents), 0)
        FROM CouponRedemption r
        WHERE r.redeemedAt >= :from AND r.redeemedAt < :to
        GROUP BY r.couponId
        ORDER BY COUNT(r) DESC
        """,
    )
    fun topByRedemptionsInPeriod(
        @Param("from") from: Instant,
        @Param("to") to: Instant,
        pageable: Pageable,
    ): List<Array<Any>>
}
