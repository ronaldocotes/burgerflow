package com.menuflow.repository.tenant

import com.menuflow.model.Order
import com.menuflow.model.OrderStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface OrderRepository :
    JpaRepository<Order, UUID>,
    JpaSpecificationExecutor<Order> {

    fun findByOrderNumber(orderNumber: String): Order?

    /**
     * Há pedido da comanda ainda em produção (PENDING/PREPARING)? Usado por
     * TableService.closeSession para impedir fechar a conta com a cozinha aberta.
     */
    fun existsByTableSession_IdAndStatusIn(sessionId: UUID, statuses: Collection<OrderStatus>): Boolean

    /**
     * KDS feed: active kitchen orders ordered by age (oldest first). The status
     * set is passed in so callers choose the scope (KDS = PENDING+PREPARING).
     */
    fun findByStatusInOrderByCreatedAtAsc(statuses: Collection<OrderStatus>): List<Order>

    /** Active kitchen orders since a timestamp (the day's start), oldest first. */
    fun findByStatusInAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
        statuses: Collection<OrderStatus>,
        from: Instant,
    ): List<Order>

    /**
     * KDS board feed (3 columns: Novos/PENDING · Em preparo/PREPARING · Prontos/READY).
     * PENDING + PREPARING are always shown regardless of age (a kitchen ticket must
     * never silently vanish), while READY is limited to [readyFrom] (start of the
     * business day in São Paulo) so the "Prontos" column is not polluted with
     * previous days' finished orders. Oldest first.
     */
    @Query(
        """
        SELECT o FROM Order o
        WHERE o.status IN (com.menuflow.model.OrderStatus.PENDING, com.menuflow.model.OrderStatus.PREPARING)
           OR (o.status = com.menuflow.model.OrderStatus.READY AND o.createdAt >= :readyFrom)
        ORDER BY o.createdAt ASC
        """,
    )
    fun findKdsBoardOrders(@Param("readyFrom") readyFrom: Instant): List<Order>

    /**
     * Active delivery orders of the day that already have a courier assigned:
     * deliveryStatus is set and not yet terminal (DELIVERED), within the window.
     */
    @Query(
        """
        SELECT o FROM Order o
        WHERE o.driverId IS NOT NULL
          AND o.deliveryStatus IS NOT NULL
          AND o.deliveryStatus <> com.menuflow.model.DeliveryStatus.DELIVERED
          AND o.createdAt >= :from
        ORDER BY o.createdAt ASC
        """,
    )
    fun findActiveDeliveryOrders(@Param("from") from: Instant): List<Order>
}
