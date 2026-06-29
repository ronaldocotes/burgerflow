package com.menuflow.controller

import com.menuflow.dto.ConversionDispatchResponse
import com.menuflow.model.ConversionStatus
import com.menuflow.service.ConversionService
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Painel de despachos de conversao (Meta CAPI + Google sGTM), Fase 3.7. Sob o
 * context-path /api/v1 (logo @RequestMapping = /conversions). Restrito a ADMIN/MANAGER
 * (gestao de marketing). db-per-tenant: a rota ja aterrissa no banco do restaurante.
 */
@RestController
@RequestMapping("/conversions")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
class ConversionController(private val service: ConversionService) {

    /** Lista paginada dos despachos (filtro opcional por status). */
    @GetMapping("/dispatches")
    fun list(
        @RequestParam(required = false) status: ConversionStatus?,
        @PageableDefault(size = 20, sort = ["createdAt"]) pageable: Pageable,
    ): Page<ConversionDispatchResponse> = service.list(status, pageable)

    /** Retry manual de um despacho (forca uma nova tentativa de envio agora). */
    @PostMapping("/dispatches/{id}/retry")
    fun retry(@PathVariable id: UUID): ResponseEntity<Void> {
        service.retryOne(id)
        return ResponseEntity.ok().build()
    }
}
