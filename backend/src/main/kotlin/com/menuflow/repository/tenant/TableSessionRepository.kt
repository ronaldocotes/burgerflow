package com.menuflow.repository.tenant

import com.menuflow.model.TableSession
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional
import java.util.UUID

interface TableSessionRepository : JpaRepository<TableSession, UUID> {

    /**
     * Sessão ATIVA (não-CLOSED) da mesa, se houver. O índice parcial
     * uq_session_active_per_table garante que existe no máximo uma.
     */
    @Query(
        """
        SELECT s FROM TableSession s
        WHERE s.table.id = :tableId
          AND s.status <> com.menuflow.model.TableSessionStatus.CLOSED
        """,
    )
    fun findActiveByTableId(@Param("tableId") tableId: UUID): Optional<TableSession>
}
