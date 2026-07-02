package com.menuflow.controller

import com.menuflow.dto.MenuLinkRequest
import com.menuflow.dto.MenuLinkResponse
import com.menuflow.service.MenuLinkService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Variantes de link/QR do cardapio (issue #11). Sob /api/v1 (logo /config/menu-links).
 * Toda a gestao e ADMIN/MANAGER — sao links publicos do restaurante. A resolucao
 * publica (sem auth) fica no PublicMenuController.
 */
@RestController
@RequestMapping("/config/menu-links")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
class MenuLinkController(private val service: MenuLinkService) {

    @GetMapping
    fun list(): List<MenuLinkResponse> = service.list()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody req: MenuLinkRequest): MenuLinkResponse = service.create(req)

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @Valid @RequestBody req: MenuLinkRequest): MenuLinkResponse =
        service.update(id, req)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) = service.delete(id)
}
