package com.menuflow.repository.tenant

import com.menuflow.model.LoyaltyReward
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
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
}
