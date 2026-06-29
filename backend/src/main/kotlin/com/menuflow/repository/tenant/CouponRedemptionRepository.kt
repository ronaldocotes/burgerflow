package com.menuflow.repository.tenant

import com.menuflow.model.CouponRedemption
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface CouponRedemptionRepository : JpaRepository<CouponRedemption, UUID> {

    /** Total de usos do cupom (limite global maxUses). */
    fun countByCouponId(couponId: UUID): Long

    /** Usos do cupom por um telefone (limite maxUsesPerCustomer). */
    fun countByCouponIdAndCustomerPhone(couponId: UUID, customerPhone: String): Long

    /** Histórico de uso de um cupom, mais recentes primeiro. */
    fun findByCouponIdOrderByRedeemedAtDesc(couponId: UUID, pageable: Pageable): Page<CouponRedemption>
}
