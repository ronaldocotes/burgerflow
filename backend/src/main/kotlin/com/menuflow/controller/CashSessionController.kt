package com.menuflow.controller

import com.menuflow.dto.CashSessionResponse
import com.menuflow.dto.CloseSessionRequest
import com.menuflow.dto.EntryRequest
import com.menuflow.dto.OpenSessionRequest
import com.menuflow.security.SecurityUtils
import com.menuflow.service.CashSessionService
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Turno de caixa. Sob o context-path /api/v1 (logo @RequestMapping = /cash-sessions).
 * Operação restrita a quem fica no caixa: ADMIN/MANAGER/CASHIER. O ator (quem abre/
 * movimenta/fecha) vem do principal assinado, não do corpo da requisição.
 */
@RestController
@RequestMapping("/cash-sessions")
class CashSessionController(private val service: CashSessionService) {

    /** Turno aberto atual: 200 com a sessão ou 204 se o caixa estiver fechado. */
    @GetMapping("/current")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    fun current(): ResponseEntity<CashSessionResponse> =
        service.current()?.let { ResponseEntity.ok(it) } ?: ResponseEntity.noContent().build()

    @PostMapping("/open")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    fun open(@Valid @RequestBody req: OpenSessionRequest): CashSessionResponse =
        service.open(actorId(), req)

    @PostMapping("/{id}/entries")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    fun addEntry(
        @PathVariable id: UUID,
        @Valid @RequestBody req: EntryRequest,
    ): CashSessionResponse = service.addEntry(id, actorId(), req)

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    fun close(
        @PathVariable id: UUID,
        @Valid @RequestBody req: CloseSessionRequest,
    ): CashSessionResponse = service.close(id, actorId(), req)

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    fun list(@PageableDefault(size = 20, sort = ["openedAt"]) pageable: Pageable): Page<CashSessionResponse> =
        service.list(pageable)

    private fun actorId(): UUID = SecurityUtils.currentPrincipalOrThrow().userId
}
