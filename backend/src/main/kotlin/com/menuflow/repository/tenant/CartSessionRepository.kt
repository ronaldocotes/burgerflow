package com.menuflow.repository.tenant

import com.menuflow.model.CartSession
import com.menuflow.model.CartSessionStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * Comandas de recuperacao de carrinho abandonado (banco do TENANT, Fase 3.5). Sem
 * filtro de escopo: db-per-tenant ja isola por banco.
 */
@Repository
interface CartSessionRepository : JpaRepository<CartSession, UUID> {

    /** Idempotencia da criacao: 1 comanda de recuperacao por pedido. */
    fun existsByOrderId(orderId: UUID): Boolean

    /** Usada pelo OrderPaidEvent para marcar RECOVERED ao pagar o pedido. */
    fun findByOrderId(orderId: UUID): CartSession?

    /**
     * Candidatas do tick do job: comandas ainda ACTIVE cujo pedido nasceu antes do
     * corte de atraso (createdAt < agora - delayMinutes). A expiracao e avaliada por
     * comanda no service (depende do prazo configurado).
     */
    fun findByStatusAndCreatedAtLessThan(status: CartSessionStatus, cutoff: Instant): List<CartSession>

    /** Listagem do admin filtrada por status. */
    fun findByStatus(status: CartSessionStatus, pageable: Pageable): Page<CartSession>
}
