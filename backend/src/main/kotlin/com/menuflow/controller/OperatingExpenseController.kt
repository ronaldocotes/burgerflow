package com.menuflow.controller

import com.menuflow.dto.OperatingExpenseRequest
import com.menuflow.dto.OperatingExpenseResponse
import com.menuflow.security.SecurityUtils
import com.menuflow.service.OperatingExpenseService
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.util.UUID

/**
 * Despesas operacionais (Fase 3.1). Sob o context-path /api/v1 (logo
 * @RequestMapping = /operating-expenses). Restrito a ADMIN/MANAGER (gestão
 * financeira). O ator de cada mutação vem do principal assinado, não do corpo.
 */
@RestController
@RequestMapping("/operating-expenses")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
class OperatingExpenseController(private val service: OperatingExpenseService) {

    @GetMapping
    fun list(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) start: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) end: LocalDate?,
        @PageableDefault(size = 20, sort = ["expenseDate"]) pageable: Pageable,
    ): Page<OperatingExpenseResponse> = service.list(start, end, pageable)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody req: OperatingExpenseRequest): OperatingExpenseResponse =
        service.create(req, actorId())

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody req: OperatingExpenseRequest,
    ): OperatingExpenseResponse = service.update(id, req, actorId())

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) = service.delete(id, actorId())

    private fun actorId(): UUID = SecurityUtils.currentPrincipalOrThrow().userId
}
