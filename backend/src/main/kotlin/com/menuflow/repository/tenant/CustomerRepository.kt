package com.menuflow.repository.tenant

import com.menuflow.model.Customer
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Cliente cadastrado do tenant (banco do TENANT). Usado pela fidelidade (Fase 3.3)
 * para creditar/consultar pontos. Sem filtro de escopo: db-per-tenant já isola por
 * banco — cada conexão aterrissa no restaurante certo.
 */
@Repository
interface CustomerRepository : JpaRepository<Customer, UUID> {
    /** Clientes ativos com opt-in de marketing (publico-alvo das campanhas, Fase 3.4). */
    fun findByMarketingOptInTrueAndActiveTrue(): List<Customer>

    /** Busca por telefone (phone_number e UNIQUE) — usado no opt-out por telefone. */
    fun findByPhoneNumber(phoneNumber: String): Customer?

    /** Clientes com pontos (no programa de fidelidade) — tool get_loyalty_stats. */
    fun countByLoyaltyPointsGreaterThan(points: Int): Long
}
