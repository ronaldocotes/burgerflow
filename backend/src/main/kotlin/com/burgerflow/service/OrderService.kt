package com.burgerflow.service

import com.burgerflow.dto.OrderRequest
import com.burgerflow.dto.OrderResponse
import com.burgerflow.exception.BusinessException
import com.burgerflow.exception.ResourceNotFoundException
import com.burgerflow.model.Order
import com.burgerflow.model.OrderItem
import com.burgerflow.model.OrderStatus
import com.burgerflow.repository.OrderItemRepository
import com.burgerflow.repository.OrderRepository
import com.burgerflow.repository.ProductRepository
import org.springframework.cache.annotation.Cacheable
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

@Service
class OrderService(
    private val orderRepository: OrderRepository,
    private val orderItemRepository: OrderItemRepository,
    private val productRepository: ProductRepository
) {
    
    companion object {
        private val orderNumberCounter = AtomicLong(0)
        private const val ORDER_NUMBER_PREFIX = "BF"
        private val dateFormatter = DateTimeFormatter.ofPattern("yyMMdd")
    }
    
    @Transactional
    fun createOrder(request: OrderRequest): OrderResponse {
        // Validate tenant
        if (request.items.isEmpty()) {
            throw BusinessException("Order must have at least one item")
        }
        
        // Check if idempotency key already exists
        if (request.idempotencyKey != null && orderRepository.existsByIdempotencyKey(request.idempotencyKey)) {
            val existingOrder = orderRepository.findByIdempotencyKey(request.idempotencyKey)!!
            return mapToOrderResponse(existingOrder)
        }
        
        // Generate order number
        val orderNumber = generateOrderNumber(request.tenantId)
        
        // Calculate totals
        var subtotal = BigDecimal.ZERO
        val orderItems = mutableListOf<OrderItem>()
        
        for ((index, itemRequest) in request.items.withIndex()) {
            val product = productRepository.findById(itemRequest.productId)
                .orElseThrow { ResourceNotFoundException("Product not found: ${itemRequest.productId}") }
            
            val unitPrice = product.price
            val totalPrice = unitPrice * BigDecimal(itemRequest.quantity)
            
            subtotal += totalPrice
            
            val orderItem = OrderItem(
                orderId = UUID.randomUUID(), // Temporary, will be updated
                productId = product.id!!,
                productSku = product.sku,
                productName = product.name,
                quantity = itemRequest.quantity,
                unitPrice = unitPrice,
                totalPrice = totalPrice,
                notes = itemRequest.notes,
                displayOrder = index,
                status = OrderStatus.PENDING
            )
            
            orderItems.add(orderItem)
        }
        
        // Calculate delivery fee based on order type
        val deliveryFee = if (request.orderType == com.burgerflow.model.OrderType.DELIVERY) {
            BigDecimal("5.00") // Fixed delivery fee for now
        } else {
            BigDecimal.ZERO
        }
        
        // Create order
        val order = Order(
            tenantId = request.tenantId,
            orderNumber = orderNumber,
            customerId = request.customerId,
            userId = null, // Will be set by security context
            orderType = com.burgerflow.model.OrderType.valueOf(request.orderType.name),
            status = OrderStatus.PENDING,
            tableNumber = request.tableNumber,
            notes = request.notes,
            subtotal = subtotal,
            discount = BigDecimal.ZERO, // Will be calculated later
            deliveryFee = deliveryFee,
            taxAmount = calculateTax(subtotal + deliveryFee),
            total = subtotal + deliveryFee + calculateTax(subtotal + deliveryFee),
            paymentMethod = request.paymentMethod?.let { com.burgerflow.model.PaymentMethod.valueOf(it.name) },
            paymentStatus = com.burgerflow.model.PaymentStatus.PENDING,
            isTakeaway = request.isTakeaway,
            priority = com.burgerflow.model.OrderPriority.valueOf(request.priority.name),
            estimatedPrepTimeMinutes = calculateEstimatedPrepTime(request.items),
            idempotencyKey = request.idempotencyKey,
            createdAt = LocalDateTime.now()
        )
        
        // Save order
        val savedOrder = orderRepository.save(order)
        
        // Update order items with correct order ID
        val savedOrderItems = orderItems.map { item ->
            item.copy(orderId = savedOrder.id!!)
        }
        
        orderItemRepository.saveAll(savedOrderItems)
        
        return mapToOrderResponse(savedOrder)
    }
    
    @Transactional
    fun getOrderById(orderId: UUID): OrderResponse {
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResourceNotFoundException("Order not found: $orderId") }
        
        return mapToOrderResponse(order)
    }
    
    @Transactional
    fun getOrderByOrderNumber(tenantId: UUID, orderNumber: String): OrderResponse {
        val order = orderRepository.findByTenantIdAndOrderNumber(tenantId, orderNumber)
            ?: throw ResourceNotFoundException("Order not found: $orderNumber")
        
        return mapToOrderResponse(order)
    }
    
    @Transactional
    @Cacheable(value = ["orders"], key = "#tenantId + '-' + #pageable.pageNumber + '-' + #pageable.pageSize")
    fun getOrdersByTenant(tenantId: UUID, pageable: Pageable): Page<OrderResponse> {
        val ordersPage = orderRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable)
        return ordersPage.map { order -> mapToOrderResponse(order) }
    }
    
    @Transactional
    fun updateOrderStatus(orderId: UUID, status: OrderStatus): OrderResponse {
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResourceNotFoundException("Order not found: $orderId") }
        
        // Validate status transition
        validateStatusTransition(order.status, status)
        
        order.status = status
        
        if (status == OrderStatus.COMPLETED) {
            order.completedAt = LocalDateTime.now()
        } else if (status == OrderStatus.CANCELLED) {
            order.cancelledAt = LocalDateTime.now()
        }
        
        val updatedOrder = orderRepository.save(order)
        return mapToOrderResponse(updatedOrder)
    }
    
    @Transactional
    fun cancelOrder(orderId: UUID, reason: String): OrderResponse {
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResourceNotFoundException("Order not found: $orderId") }
        
        if (!order.canBeCancelled()) {
            throw BusinessException("Order cannot be cancelled in current status: ${order.status}")
        }
        
        order.status = OrderStatus.CANCELLED
        order.cancelledAt = LocalDateTime.now()
        order.cancelledReason = reason
        
        val updatedOrder = orderRepository.save(order)
        return mapToOrderResponse(updatedOrder)
    }
    
    @Transactional
    fun updatePaymentStatus(orderId: UUID, paymentStatus: com.burgerflow.model.PaymentStatus, reference: String? = null): OrderResponse {
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResourceNotFoundException("Order not found: $orderId") }
        
        order.paymentStatus = paymentStatus
        order.paymentReference = reference
        
        // If payment is confirmed, update order status
        if (paymentStatus == com.burgerflow.model.PaymentStatus.PAID && order.status == OrderStatus.PENDING) {
            order.status = OrderStatus.IN_PREPARATION
        }
        
        val updatedOrder = orderRepository.save(order)
        return mapToOrderResponse(updatedOrder)
    }
    
    private fun generateOrderNumber(tenantId: UUID): String {
        val datePart = LocalDateTime.now().format(dateFormatter)
        val sequencePart = orderNumberCounter.incrementAndGet()
        return "$ORDER_NUMBER_PREFIX-$datePart-${sequencePart.toString().padStart(6, '0')}"
    }
    
    private fun calculateTax(amount: BigDecimal): BigDecimal {
        // 10% tax for now
        return amount * BigDecimal("0.10")
    }
    
    private fun calculateEstimatedPrepTime(items: List<com.burgerflow.dto.OrderItemRequest>): Int {
        // Calculate based on items - average 10 minutes per item
        return (items.size * 10).coerceAtLeast(15)
    }
    
    private fun validateStatusTransition(current: OrderStatus, next: OrderStatus) {
        val validTransitions = mapOf(
            OrderStatus.PENDING to listOf(
                OrderStatus.IN_PREPARATION,
                OrderStatus.CANCELLED
            ),
            OrderStatus.IN_PREPARATION to listOf(
                OrderStatus.READY_FOR_DELIVERY,
                OrderStatus.CANCELLED
            ),
            OrderStatus.READY_FOR_DELIVERY to listOf(
                OrderStatus.IN_DELIVERY,
                OrderStatus.CANCELLED
            ),
            OrderStatus.IN_DELIVERY to listOf(
                OrderStatus.COMPLETED,
                OrderStatus.CANCELLED
            ),
            OrderStatus.COMPLETED to listOf<OrderStatus>(),
            OrderStatus.CANCELLED to listOf<OrderStatus>()
        )
        
        if (next !in validTransitions[current]!!) {
            throw BusinessException("Invalid status transition from $current to $next")
        }
    }
    
    private fun mapToOrderResponse(order: Order): OrderResponse {
        return OrderResponse(
            id = order.id!!,
            orderNumber = order.orderNumber,
            tenantId = order.tenantId,
            customerId = order.customerId,
            customerName = null, // Will be populated later
            customerPhone = null, // Will be populated later
            userId = order.userId,
            userName = null, // Will be populated later
            orderType = order.orderType,
            status = order.status,
            tableNumber = order.tableNumber,
            items = emptyList(), // Will be populated later
            subtotal = order.subtotal,
            discount = order.discount,
            deliveryFee = order.deliveryFee,
            taxAmount = order.taxAmount,
            total = order.total,
            paymentMethod = order.paymentMethod,
            paymentStatus = order.paymentStatus,
            paymentReference = order.paymentReference,
            isTakeaway = order.isTakeaway,
            priority = order.priority,
            estimatedPrepTimeMinutes = order.estimatedPrepTimeMinutes,
            notes = order.notes,
            createdAt = order.createdAt,
            updatedAt = order.updatedAt,
            completedAt = order.completedAt
        )
    }
}

// Data classes for DTOs
// These would be in separate files in a real project
