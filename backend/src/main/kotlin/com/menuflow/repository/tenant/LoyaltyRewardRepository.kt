package com.menuflow.repository.tenant

import com.menuflow.model.LoyaltyReward
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/** Recompensas (punches) de fidelidade no banco do TENANT. */
@Repository
interface LoyaltyRewardRepository : JpaRepository<LoyaltyReward, UUID> {

    /** Quantos punches o cliente tem disponíveis (ainda não resgatados). */
    fun countByCustomerIdAndRedeemedAtIsNull(customerId: UUID): Long

    /** Primeiro punch disponível do cliente (para o frontend usar no resgate). */
    fun findFirstByCustomerIdAndRedeemedAtIsNull(customerId: UUID): LoyaltyReward?

    /** Total de recompensas ainda não resgatadas (tool get_loyalty_stats). */
    fun countByRedeemedAtIsNull(): Long

    /**
     * Quantas recompensas foram resgatadas (redeemedAt) no período [from, to).
     * Usado pelo sumário gerencial de fidelidade.
     */
    @Query(
        "SELECT COUNT(r) FROM LoyaltyReward r WHERE r.redeemedAt >= :from AND r.redeemedAt < :to",
    )
    fun countRedeemedInPeriod(@Param("from") from: Instant, @Param("to") to: Instant): Long
}
