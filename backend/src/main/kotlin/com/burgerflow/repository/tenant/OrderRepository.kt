package com.burgerflow.repository.tenant

import com.burgerflow.model.Order
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface OrderRepository :
    JpaRepository<Order, UUID>,
    JpaSpecificationExecutor<Order> {

    fun findByOrderNumber(orderNumber: String): Order?
}
