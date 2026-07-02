package com.menuflow.controller

import com.menuflow.dto.CancellationReasonRequest
import com.menuflow.dto.CancellationReasonResponse
import com.menuflow.service.CancellationReasonService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Motivos de cancelamento (issue #10). Sob /api/v1 (logo /config/cancellation-reasons).
 * Leitura aberta as funcoes operacionais (o KDS/PDV precisa listar os motivos ativos
 * ao cancelar); escrita e gestao (ADMIN/MANAGER).
 */
@RestController
@RequestMapping("/config/cancellation-reasons")
class CancellationReasonController(private val service: CancellationReasonService) {

    /** [activeOnly]=true (default) devolve so os ativos; false lista todos (admin). */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER','WAITER','STAFF','KITCHEN','OPERATOR')")
    fun list(@RequestParam(defaultValue = "true") activeOnly: Boolean): List<CancellationReasonResponse> =
        service.list(activeOnly)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun create(@Valid @RequestBody req: CancellationReasonRequest): CancellationReasonResponse =
        service.create(req)

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun update(@PathVariable id: UUID, @Valid @RequestBody req: CancellationReasonRequest): CancellationReasonResponse =
        service.update(id, req)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun deactivate(@PathVariable id: UUID) = service.deactivate(id)
}
