package com.menuflow.repository.tenant

import com.menuflow.model.DriverSettlement
import com.menuflow.model.DriverSettlementStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface DriverSettlementRepository : JpaRepository<DriverSettlement, UUID> {

    /** Ha acerto nesse status para o entregador? Barra um segundo OPEN (409). */
    fun existsByDriverIdAndStatus(driverId: UUID, status: DriverSettlementStatus): Boolean

    // Filtros da listagem paginada (4 combinacoes; evita o JPQL com param NULL,
    // que no Postgres da "could not determine data type of parameter").
    fun findByDriverIdAndStatus(driverId: UUID, status: DriverSettlementStatus, pageable: Pageable): Page<DriverSettlement>

    fun findByDriverId(driverId: UUID, pageable: Pageable): Page<DriverSettlement>

    fun findByStatus(status: DriverSettlementStatus, pageable: Pageable): Page<DriverSettlement>
}
