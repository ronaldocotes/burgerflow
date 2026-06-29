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
}
