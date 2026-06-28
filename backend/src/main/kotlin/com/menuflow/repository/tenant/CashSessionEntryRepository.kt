package com.menuflow.repository.tenant

import com.menuflow.model.CashSessionEntry
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface CashSessionEntryRepository : JpaRepository<CashSessionEntry, UUID> {

    /** Movimentos (sangrias/reforços) de um turno, para somar no esperado. */
    fun findAllBySessionId(sessionId: UUID): List<CashSessionEntry>
}
