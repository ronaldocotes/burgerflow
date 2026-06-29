package com.menuflow.repository.tenant

import com.menuflow.model.OrderItem
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface OrderItemRepository : JpaRepository<OrderItem, UUID> {

    /**
     * Mais vendidos para a vitrine do cardapio publico. Conta itens de pedidos
     * NAO cancelados, agrupa por produto e devolve apenas os ids dos produtos
     * com pelo menos 3 ocorrencias, do mais vendido para o menos vendido.
     *
     * Seguranca: retorna SOMENTE os UUIDs dos produtos. Nunca contagem nem
     * receita -- esses sao numeros de negocio que nao podem vazar no endpoint
     * publico (o cliente final ve so "mais pedidos", nao o volume real).
     */
    @Query(
        """
        SELECT oi.productId FROM OrderItem oi
        WHERE oi.orderId IN (
            SELECT o.id FROM Order o WHERE o.status <> com.menuflow.model.OrderStatus.CANCELLED
        )
        GROUP BY oi.productId
        HAVING COUNT(oi) >= 3
        ORDER BY COUNT(oi) DESC
        """,
    )
    fun findTopProductIds(pageable: Pageable): List<UUID>

    /**
     * Mais vendidos do periodo para o Copiloto (Fase 4.1). DIFERENTE de
     * [findTopProductIds] (vitrine publica, so ids): aqui o consumidor e o DONO do
     * restaurante, entao pode ver contagem e receita. Cada linha e
     * [productName, COUNT(itens), SUM(total_price_cents)] dos pedidos NAO cancelados
     * criados desde [from], do mais vendido para o menos. Limite via Pageable.
     */
    @Query(
        """
        SELECT oi.productName, COUNT(oi), COALESCE(SUM(oi.totalPriceCents), 0)
        FROM OrderItem oi
        WHERE oi.orderId IN (
            SELECT o.id FROM Order o
            WHERE o.status <> com.menuflow.model.OrderStatus.CANCELLED
              AND o.createdAt >= :from
        )
        GROUP BY oi.productName
        ORDER BY COUNT(oi) DESC
        """,
    )
    fun topProductsSince(@Param("from") from: Instant, pageable: Pageable): List<Array<Any>>
}
