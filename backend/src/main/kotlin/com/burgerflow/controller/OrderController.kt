package com.burgerflow.controller

import com.burgerflow.dto.OrderCreateRequest
import com.burgerflow.dto.OrderResponse
import com.burgerflow.dto.OrderStatusUpdateRequest
import com.burgerflow.exception.BusinessException
import com.burgerflow.model.OrderStatus
import com.burgerflow.security.SecurityUtils
import com.burgerflow.service.IdempotencyService
import com.burgerflow.service.OrderService
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.net.URI
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/orders")
class OrderController(
    private val orderService: OrderService,
    private val idempotencyService: IdempotencyService,
) {

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','STAFF','CASHIER','KITCHEN')")
    fun list(
        @RequestParam(required = false) status: OrderStatus?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant?,
        @PageableDefault(size = 20, sort = ["createdAt"]) pageable: Pageable,
    ): Page<OrderResponse> = orderService.list(status, from, to, pageable)

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','STAFF','CASHIER','KITCHEN')")
    fun get(@PathVariable id: UUID): OrderResponse = orderService.get(id)

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','STAFF','CASHIER')")
    fun create(
        @RequestHeader("Idempotency-Key") idempotencyKey: String?,
        @Valid @RequestBody req: OrderCreateRequest,
    ): ResponseEntity<OrderResponse> {
        if (idempotencyKey.isNullOrBlank()) {
            throw BusinessException("Idempotency-Key header is required")
        }
        val hash = idempotencyService.hash(req)
        idempotencyService.find(idempotencyKey, "orders", hash)?.let { stored ->
            val cached = idempotencyService.deserialize(stored.body, OrderResponse::class.java)
            return ResponseEntity.status(stored.status).body(cached)
        }
        val userId = SecurityUtils.currentPrincipal()?.userId
        val created = orderService.create(req, userId)
        idempotencyService.save(idempotencyKey, "orders", hash, HttpStatus.CREATED.value(), created)
        return ResponseEntity.created(URI.create("/api/v1/orders/${created.id}")).body(created)
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','STAFF','KITCHEN')")
    fun updateStatus(
        @PathVariable id: UUID,
        @Valid @RequestBody req: OrderStatusUpdateRequest,
    ): OrderResponse = orderService.updateStatus(id, req)
}
