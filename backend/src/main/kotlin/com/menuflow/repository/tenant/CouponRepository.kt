package com.menuflow.repository.tenant

import com.menuflow.model.Coupon
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface CouponRepository : JpaRepository<Coupon, UUID> {

    /** Lookup pela chave natural (code já normalizado em maiúsculas pelo serviço). */
    fun findByCode(code: String): Coupon?

    /** Lista filtrada por situação (ativos/inativos). */
    fun findByActive(active: Boolean, pageable: Pageable): Page<Coupon>

    /**
     * Lookup do cupom com LOCK PESSIMISTA na linha, usado no fluxo de redenção
     * (OrderService.create). Travar a linha do cupom serializa redenções concorrentes
     * do MESMO cupom -> a contagem de usos é consistente e o maxUses não estoura em
     * corrida (Postgres não permite FOR UPDATE com agregado/COUNT; travar a linha-pai
     * é o caminho correto). O lock é liberado no commit da transação do pedido.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Coupon c WHERE c.code = :code")
    fun findByCodeForUpdate(@Param("code") code: String): Coupon?
}
