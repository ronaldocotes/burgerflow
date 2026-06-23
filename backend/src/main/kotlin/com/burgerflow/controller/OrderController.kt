package com.burgerflow.controller

import com.burgerflow.dto.OrderRequest
import com.burgerflow.dto.OrderResponse
import com.burgerflow.model.OrderStatus
import com.burgerflow.model.PaymentStatus
import com.burgerflow.service.OrderService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springdoc.api.annotations.ParameterObject
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Operations related to orders")
class OrderController(private val orderService: OrderService) {
    
    @PostMapping
    @Operation(summary = "Create a new order")
    fun createOrder(@Valid @RequestBody request: OrderRequest): ResponseEntity<OrderResponse> {
        val response = orderService.createOrder(request)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }
    
    @GetMapping("/{orderId}")
    @Operation(summary = "Get order by ID")
    fun getOrderById(@PathVariable orderId: UUID): ResponseEntity<OrderResponse> {
        val response = orderService.getOrderById(orderId)
        return ResponseEntity.ok(response)
    }
    
    @GetMapping("/number/{orderNumber}")
    @Operation(summary = "Get order by order number")
    fun getOrderByOrderNumber(
        @RequestHeader("X-Tenant-ID") tenantId: UUID,
        @PathVariable orderNumber: String
    ): ResponseEntity<OrderResponse> {
        val response = orderService.getOrderByOrderNumber(tenantId, orderNumber)
        return ResponseEntity.ok(response)
    }
    
    @GetMapping("/tenant/{tenantId}")
    @Operation(summary = "Get all orders for a tenant")
    fun getOrdersByTenant(
        @PathVariable tenantId: UUID,
        @ParameterObject @PageableDefault(size = 20, sort = ["createdAt,desc"]) pageable: Pageable
    ): ResponseEntity<Page<OrderResponse>> {
        val response = orderService.getOrdersByTenant(tenantId, pageable)
        return ResponseEntity.ok(response)
    }
    
    @PatchMapping("/{orderId}/status")
    @Operation(summary = "Update order status")
    fun updateOrderStatus(
        @PathVariable orderId: UUID,
        @RequestParam status: OrderStatus
    ): ResponseEntity<OrderResponse> {
        val response = orderService.updateOrderStatus(orderId, status)
        return ResponseEntity.ok(response)
    }
    
    @PatchMapping("/{orderId}/cancel")
    @Operation(summary = "Cancel an order")
    fun cancelOrder(
        @PathVariable orderId: UUID,
        @RequestParam reason: String? = null
    ): ResponseEntity<OrderResponse> {
        val response = orderService.cancelOrder(orderId, reason ?: "Cancelled by user")
        return ResponseEntity.ok(response)
    }
    
    @PatchMapping("/{orderId}/payment")
    @Operation(summary = "Update payment status")
    fun updatePaymentStatus(
        @PathVariable orderId: UUID,
        @RequestParam paymentStatus: PaymentStatus,
        @RequestParam(required = false) reference: String? = null
    ): ResponseEntity<OrderResponse> {
        val response = orderService.updatePaymentStatus(orderId, paymentStatus, reference)
        return ResponseEntity.ok(response)
    }
    
    @GetMapping("/tenant/{tenantId}/pending")
    @Operation(summary = "Get pending orders for a tenant")
    fun getPendingOrders(
        @PathVariable tenantId: UUID,
        @ParameterObject @PageableDefault(size = 20, sort = ["createdAt,asc"]) pageable: Pageable
    ): ResponseEntity<Page<OrderResponse>> {
        // This would be implemented with a filter in the service
        // For now, returning all orders
        val response = orderService.getOrdersByTenant(tenantId, pageable)
        return ResponseEntity.ok(response)
    }
    
    @GetMapping("/tenant/{tenantId}/in-preparation")
    @Operation(summary = "Get orders in preparation for a tenant")
    fun getInPreparationOrders(
        @PathVariable tenantId: UUID,
        @ParameterObject @PageableDefault(size = 20, sort = ["createdAt,asc"]) pageable: Pageable
    ): ResponseEntity<Page<OrderResponse>> {
        val response = orderService.getOrdersByTenant(tenantId, pageable)
        return ResponseEntity.ok(response)
    }
}
