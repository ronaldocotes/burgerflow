package com.menuflow.repository.tenant

import com.menuflow.model.CashSession
import com.menuflow.model.CashSessionStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface CashSessionRepository : JpaRepository<CashSession, UUID> {

    /** Turno aberto do restaurante (no máximo um, garantido por índice parcial). */
    fun findFirstByStatus(status: CashSessionStatus): CashSession?

    /** Há turno nesse status? Usado para barrar um segundo open (409). */
    fun existsByStatus(status: CashSessionStatus): Boolean
}
