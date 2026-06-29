package com.menuflow.repository.tenant

import com.menuflow.model.BotHandoff
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface BotHandoffRepository : JpaRepository<BotHandoff, UUID> {

    /** Existe handoff ATIVO (nao resolvido) para este telefone? Se sim, o bot silencia. */
    fun existsByCustomerPhoneAndResolvedFalse(customerPhone: String): Boolean

    /** Lista de handoffs filtrada por situacao (resolved=false = pendentes), paginada. */
    fun findByResolvedOrderByCreatedAtDesc(resolved: Boolean, pageable: Pageable): Page<BotHandoff>
}
