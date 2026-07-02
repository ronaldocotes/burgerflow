package com.menuflow.controller

import com.menuflow.dto.EntryPopupResponse
import com.menuflow.dto.EntryPopupUpdateRequest
import com.menuflow.service.EntryPopupService
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Pop-up de entrada do cardapio publico (issue #13). Sob /api/v1/config/entry-popup.
 * Leitura liberada as funcoes operacionais (mesma politica do GET /config); a
 * escrita e gestao (ADMIN/MANAGER). O PUT substitui o pop-up inteiro de forma
 * atomica (enabled + titulo + ate 3 produtos).
 */
@RestController
@RequestMapping("/config/entry-popup")
class EntryPopupController(private val service: EntryPopupService) {

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER','WAITER','STAFF','KITCHEN','OPERATOR')")
    fun get(): EntryPopupResponse = service.get()

    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun update(@Valid @RequestBody req: EntryPopupUpdateRequest): EntryPopupResponse =
        service.update(req)
}
