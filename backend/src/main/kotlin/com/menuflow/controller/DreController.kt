package com.menuflow.controller

import com.menuflow.dto.DreResponse
import com.menuflow.service.DreService
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * DRE Automático (Fase 3.1). Sob o context-path /api/v1 (logo @RequestMapping =
 * /dre). Restrito a ADMIN/MANAGER: o resultado financeiro do restaurante é
 * informação de gestão. Tudo no banco do tenant (rota pelo token assinado).
 */
@RestController
@RequestMapping("/dre")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
class DreController(private val service: DreService) {

    /** DRE completo de um intervalo de datas (inclusivo). */
    @GetMapping
    fun dre(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) start: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) end: LocalDate,
    ): DreResponse = service.compute(start, end)

    /** Atalho de período: today | week | month (até hoje). */
    @GetMapping("/summary")
    fun summary(@RequestParam period: String): DreResponse = service.summary(period)
}
