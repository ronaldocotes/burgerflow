package com.burgerflow.repository

import com.burgerflow.model.Order
import com.burgerflow.model.OrderStatus
import com.burgerflow.model.OrderType
import com.burgerflow.model.PaymentStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.UUID

@Repository
interface OrderRepository : JpaRepository<Order, UUID> {
    
    fun findByTenantId(tenantId: UUID): List<Order>
    
    fun findByTenantIdAndOrderNumber(tenantId: UUID, orderNumber: String): Order?
    
    fun findByTenantIdAndStatus(tenantId: UUID, status: OrderStatus): List<Order>
    
    fun findByTenantIdAndOrderType(tenantId: UUID, orderType: OrderType): List<Order>
    
    fun findByTenantIdAndPaymentStatus(tenantId: UUID, paymentStatus: PaymentStatus): List<Order>
    
    fun findByTenantIdAndCustomerId(tenantId: UUID, customerId: UUID): List<Order>
    
    fun findByTenantIdAndUserId(tenantId: UUID, userId: UUID): List<Order>
    
    fun findByTenantIdAndCreatedAtBetween(tenantId: UUID, start: LocalDateTime, end: LocalDateTime): List<Order>
    
    fun findByTenantIdAndStatusIn(tenantId: UUID, statuses: List<OrderStatus>): List<Order>
    
    fun findByTenantIdAndCreatedAtGreaterThanEqual(tenantId: UUID, date: LocalDateTime): List<Order>
    
    fun findByIdempotencyKey(idempotencyKey: String): Order?
    
    fun existsByIdempotencyKey(idempotencyKey: String): Boolean
    
    fun findByTenantIdOrderByCreatedAtDesc(tenantId: UUID, pageable: Pageable): Page<Order>
    
    @Query("SELECT o FROM Order o WHERE o.tenantId = :tenantId AND o.status IN :statuses AND o.createdAt >= :start AND o.createdAt <= :end")
    fun findByTenantIdAndStatusInAndDateRange(
        tenantId: UUID,
        statuses: List<OrderStatus>,
        start: LocalDateTime,
        end: LocalDateTime
    ): List<Order>
    
    @Query("SELECT COUNT(o) FROM Order o WHERE o.tenantId = :tenantId AND o.status = 'COMPLETED'")
    fun countCompletedOrders(tenantId: UUID): Long
    
    @Query("SELECT COUNT(o) FROM Order o WHERE o.tenantId = :tenantId AND o.status = 'PENDING'")
    fun countPendingOrders(tenantId: UUID): Long
    
    @Query("SELECT COUNT(o) FROM Order o WHERE o.tenantId = :tenantId AND o.status = 'IN_PREPARATION'")
    fun countInPreparationOrders(tenantId: UUID): Long
    
    @Query("SELECT COALESCE(SUM(o.total), 0) FROM Order o WHERE o.tenantId = :tenantId AND o.status = 'COMPLETED' AND o.createdAt >= :start AND o.createdAt <= :end")
    fun calculateRevenue(tenantId: UUID, start: LocalDateTime, end: LocalDateTime): Double
    
    @Query("SELECT COUNT(DISTINCT o.customerId) FROM Order o WHERE o.tenantId = :tenantId AND o.createdAt >= :start AND o.createdAt <= :end")
    fun countUniqueCustomers(tenantId: UUID, start: LocalDateTime, end: LocalDateTime): Long
    
    @Query("""
        SELECT DATE(o.createdAt) as date, COUNT(o) as count 
        FROM Order o 
        WHERE o.tenantId = :tenantId AND o.createdAt >= :start AND o.createdAt <= :end
        GROUP BY DATE(o.createdAt)
        ORDER BY DATE(o.createdAt)
    """)
    fun countOrdersByDate(tenantId: UUID, start: LocalDateTime, end: LocalDateTime): List<Any>
    
    fun deleteByTenantId(tenantId: UUID): Int
}
