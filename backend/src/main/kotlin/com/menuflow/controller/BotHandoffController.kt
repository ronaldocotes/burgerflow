package com.menuflow.controller

import com.menuflow.dto.BotHandoffResponse
import com.menuflow.service.WhatsAppBotService
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Gestao dos handoffs do bot (Fase 4.3). Sob o context-path /api/v1 (logo /bot/handoffs).
 * Apenas gestao (ADMIN/MANAGER) ve e encerra transferencias. O tenant vem do token
 * assinado (JwtAuthFilter) — isolamento garantido (db-per-tenant).
 */
@RestController
@RequestMapping("/bot/handoffs")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
class BotHandoffController(private val botService: WhatsAppBotService) {

    @GetMapping
    fun list(
        @RequestParam(defaultValue = "false") resolved: Boolean,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): Page<BotHandoffResponse> =
        botService.listHandoffs(resolved, PageRequest.of(page, size.coerceIn(1, 100)))
            .map { BotHandoffResponse.from(it) }

    @PostMapping("/{id}/resolve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun resolve(@PathVariable id: UUID) {
        botService.resolveHandoff(id)
    }
}
