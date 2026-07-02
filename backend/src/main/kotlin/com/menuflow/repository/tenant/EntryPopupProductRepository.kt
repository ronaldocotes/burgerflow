package com.menuflow.repository.tenant

import com.menuflow.model.EntryPopupProduct
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface EntryPopupProductRepository : JpaRepository<EntryPopupProduct, UUID> {
    /** Destaques do pop-up na ordem de exibicao definida pelo dono. */
    fun findAllByOrderBySortOrderAsc(): List<EntryPopupProduct>
}
