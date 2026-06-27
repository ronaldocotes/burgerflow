package com.menuflow.repository.tenant

import com.menuflow.model.RestaurantTable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface RestaurantTableRepository : JpaRepository<RestaurantTable, UUID> {

    /** Mesas ativas, na ordem de exibição do salão (sort_order, depois rótulo). */
    fun findByActiveTrueOrderBySortOrderAscLabelAsc(): List<RestaurantTable>

    /** Há uma mesa ATIVA com este rótulo? (rótulo é único só entre ativas — V11). */
    fun existsByLabelAndActiveTrue(label: String): Boolean
}
