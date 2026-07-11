package com.menuflow.controller

import com.menuflow.dto.DeliveryZonesRequest
import com.menuflow.dto.DeliveryZonesResponse
import com.menuflow.service.DeliveryZoneService
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Config de zonas de entrega por raio (issue #2). Rota flat + RBAC de gestao
 * (ADMIN/MANAGER), como as demais telas de config. db-per-tenant garante o
 * isolamento (a rota ja aterrissa no banco do restaurante).
 *
 *  - GET  /delivery/zones : conjunto atual de aneis + limiar de frete gratis por valor.
 *  - PUT  /delivery/zones : substitui o conjunto INTEIRO de uma vez (idempotente);
 *    valida sobreposicao/ordem crescente de raios, fee >= 0 (teto) e eta_min <= eta_max.
 */
@RestController
@RequestMapping("/delivery/zones")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
class DeliveryZoneController(
    private val service: DeliveryZoneService,
) {

    @GetMapping
    fun get(): DeliveryZonesResponse = service.get()

    @PutMapping
    fun replace(@Valid @RequestBody req: DeliveryZonesRequest): DeliveryZonesResponse =
        service.replace(req)
}
