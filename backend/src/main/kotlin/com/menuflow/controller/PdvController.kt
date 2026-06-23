package com.menuflow.controller

import com.menuflow.dto.OrderResponse
import com.menuflow.dto.PaymentResponse
import com.menuflow.dto.PdvOrderCreateRequest
import com.menuflow.dto.PdvPaymentRequest
import com.menuflow.exception.BusinessException
import com.menuflow.security.SecurityUtils
import com.menuflow.service.IdempotencyService
import com.menuflow.service.OrderService
import com.menuflow.service.PdvService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.net.URI
import java.util.UUID

/**
 * Point of Sale endpoints (Sprint 2). RBAC: OPERATOR or ADMIN.
 *
 * Order creation requires an Idempotency-Key (operator double-click / flaky
 * mobile network must not create a duplicate sale + double stock decrement).
 */
@RestController
@RequestMapping("/pdv")
@PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
class PdvController(
    private val pdvService: PdvService,
    private val orderService: OrderService,
    private val idempotencyService: IdempotencyService,
) {

    @PostMapping("/orders")
    fun create(
        @RequestHeader("Idempotency-Key") idempotencyKey: String?,
        @Valid @RequestBody req: PdvOrderCreateRequest,
    ): ResponseEntity<OrderResponse> {
        if (idempotencyKey.isNullOrBlank()) {
            throw BusinessException("Idempotency-Key header is required")
        }
        val hash = idempotencyService.hash(req)
        idempotencyService.find(idempotencyKey, "pdv-orders", hash)?.let { stored ->
            val cached = idempotencyService.deserialize(stored.body, OrderResponse::class.java)
            return ResponseEntity.status(stored.status).body(cached)
        }
        val userId = SecurityUtils.currentPrincipal()?.userId
        val created = pdvService.createOrder(req, userId)
        idempotencyService.save(idempotencyKey, "pdv-orders", hash, HttpStatus.CREATED.value(), created)
        return ResponseEntity.created(URI.create("/api/v1/pdv/orders/${created.id}")).body(created)
    }

    /** Active PDV orders of the day: PENDING + PREPARING + READY, oldest first. */
    @GetMapping("/orders/active")
    fun active(): List<OrderResponse> = orderService.pdvActiveOrders()

    @PostMapping("/orders/{id}/pay")
    fun pay(
        @PathVariable id: UUID,
        @Valid @RequestBody req: PdvPaymentRequest,
    ): PaymentResponse = pdvService.pay(id, req)
}
