package com.burgerflow.repository.tenant

import com.burgerflow.model.Order
import com.burgerflow.model.OrderStatus
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
     * Active delivery orders of the day that already have a courier assigned:
     * deliveryStatus is set and not yet terminal (DELIVERED), within the window.
     */
    @Query(
        """
        SELECT o FROM Order o
        WHERE o.driverId IS NOT NULL
          AND o.deliveryStatus IS NOT NULL
          AND o.deliveryStatus <> com.burgerflow.model.DeliveryStatus.DELIVERED
          AND o.createdAt >= :from
        ORDER BY o.createdAt ASC
        """,
    )
    fun findActiveDeliveryOrders(@Param("from") from: Instant): List<Order>
}
