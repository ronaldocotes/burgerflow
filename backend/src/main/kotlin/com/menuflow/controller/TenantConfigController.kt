package com.menuflow.controller

import com.menuflow.dto.TenantConfigResponse
import com.menuflow.dto.TenantConfigUpdateRequest
import com.menuflow.service.TenantConfigService
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Configurações operacionais do tenant. Sob o context-path /api/v1 (logo
 * @RequestMapping = /config). Leitura aberta às funções operacionais (PDV/KDS/
 * salão podem precisar saber se o aceite automático está ligado); a escrita é
 * gestão (ADMIN/MANAGER) — só quem administra o restaurante muda a política.
 */
@RestController
@RequestMapping("/config")
class TenantConfigController(private val service: TenantConfigService) {

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER','WAITER','STAFF','KITCHEN','OPERATOR')")
    fun get(): TenantConfigResponse = service.get()

    @PatchMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun update(@Valid @RequestBody req: TenantConfigUpdateRequest): TenantConfigResponse =
        service.update(req)
}
