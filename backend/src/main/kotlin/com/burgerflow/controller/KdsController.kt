package com.burgerflow.controller

import com.burgerflow.dto.KdsOrderView
import com.burgerflow.service.OrderService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Kitchen Display System REST surface (Sprint 2). The live feed is over STOMP
 * (/topic/kds/{tenantSlug}); this endpoint provides the initial snapshot the
 * kitchen screen loads on open. Scoped to the tenant by the routed datasource.
 */
@RestController
@RequestMapping("/kds")
class KdsController(
    private val orderService: OrderService,
) {

    /** Active kitchen queue: PENDING + PREPARING, oldest first. */
    @GetMapping("/orders")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','STAFF','KITCHEN','OPERATOR')")
    fun activeOrders(): List<KdsOrderView> = orderService.kdsActiveOrders()
}
