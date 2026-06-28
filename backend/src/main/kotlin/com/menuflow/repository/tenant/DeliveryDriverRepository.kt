package com.menuflow.repository.tenant

import com.menuflow.model.DeliveryDriver
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface DeliveryDriverRepository : JpaRepository<DeliveryDriver, UUID> {
    fun findByActiveTrueOrderByNameAsc(): List<DeliveryDriver>

    fun findAllByOrderByNameAsc(): List<DeliveryDriver>
}
