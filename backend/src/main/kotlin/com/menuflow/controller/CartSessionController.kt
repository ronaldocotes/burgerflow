package com.menuflow.controller

import com.menuflow.dto.CartSessionResponse
import com.menuflow.model.CartSessionStatus
import com.menuflow.service.CartRecoveryService
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Painel de recuperacao de carrinho abandonado (Fase 3.5). Sob o context-path /api/v1
 * (logo @RequestMapping = /cart-sessions). Leitura restrita a ADMIN/MANAGER (gestao do
 * funil). db-per-tenant: a rota ja aterrissa no banco do restaurante.
 */
@RestController
@RequestMapping("/cart-sessions")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
class CartSessionController(private val service: CartRecoveryService) {

    @GetMapping
    fun list(
        @RequestParam(required = false) status: CartSessionStatus?,
        @PageableDefault(size = 20, sort = ["createdAt"]) pageable: Pageable,
    ): Page<CartSessionResponse> = service.list(status, pageable)
}
