package com.menuflow.repository.tenant

import com.menuflow.model.CancellationReason
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface CancellationReasonRepository : JpaRepository<CancellationReason, UUID> {

    /** Todos os motivos na ordem de exibicao (config admin). */
    fun findAllByOrderBySortOrderAscDescriptionAsc(): List<CancellationReason>

    /** So os motivos ativos (seletor do fluxo de cancelamento). */
    fun findAllByActiveTrueOrderBySortOrderAscDescriptionAsc(): List<CancellationReason>
}
