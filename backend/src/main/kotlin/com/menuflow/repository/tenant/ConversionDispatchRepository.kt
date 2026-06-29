package com.menuflow.repository.tenant

import com.menuflow.model.ConversionDispatch
import com.menuflow.model.ConversionPlatform
import com.menuflow.model.ConversionStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ConversionDispatchRepository : JpaRepository<ConversionDispatch, UUID> {

    /** Idempotencia: ja existe despacho deste pedido para esta plataforma? */
    fun existsByOrderIdAndPlatform(orderId: UUID, platform: ConversionPlatform): Boolean

    /** Despachos em um conjunto de status (usado pelo job: PENDING + FAILED). */
    fun findByStatusIn(statuses: Collection<ConversionStatus>): List<ConversionDispatch>

    /** Listagem paginada por status (painel admin). */
    fun findByStatus(status: ConversionStatus, pageable: Pageable): Page<ConversionDispatch>
}
