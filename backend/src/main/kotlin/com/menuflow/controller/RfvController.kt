package com.menuflow.controller

import com.menuflow.dto.RfvScoreResponse
import com.menuflow.model.RfvSegment
import com.menuflow.service.RfvService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Classificacao RFV dos clientes (Fase 3.4). Sob o context-path /api/v1 (logo
 * @RequestMapping = /rfv). Restrito a ADMIN/MANAGER (dado analitico de negocio).
 * Filtro opcional por segmento. db-per-tenant: ja aterrissa no banco do restaurante.
 */
@RestController
@RequestMapping("/rfv")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
class RfvController(private val service: RfvService) {

    @GetMapping
    fun scores(@RequestParam(required = false) segment: RfvSegment?): List<RfvScoreResponse> =
        service.scoresBySegment(segment).map { RfvScoreResponse.from(it) }
}
