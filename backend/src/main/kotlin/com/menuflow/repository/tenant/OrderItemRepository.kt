package com.menuflow.repository.tenant

import com.menuflow.model.OrderItem
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
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
}
