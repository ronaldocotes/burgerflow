package com.menuflow.controller

import com.menuflow.dto.AuditLogResponse
import com.menuflow.service.AuditLogService
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Consulta da trilha de auditoria do tenant (banco do tenant). ADMIN/MANAGER.
 * Filtros opcionais por entity e entityId; ordenado por mais recente primeiro.
 */
@RestController
@RequestMapping("/audit-log")
class AuditLogController(private val service: AuditLogService) {

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun list(
        @RequestParam(required = false) entity: String?,
        @RequestParam(required = false) entityId: UUID?,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): Page<AuditLogResponse> = service.list(entity, entityId, pageable)
}
