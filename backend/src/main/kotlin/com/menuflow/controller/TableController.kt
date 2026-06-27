package com.menuflow.controller

import com.menuflow.dto.TableCreateRequest
import com.menuflow.dto.TableDto
import com.menuflow.dto.TableUpdateRequest
import com.menuflow.security.SecurityUtils
import com.menuflow.service.TableService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Mesas e Comandas. Sob o context-path /api/v1 (logo @RequestMapping = /tables).
 * Leitura aberta ao salão (inclui WAITER/KITCHEN); CRUD de mesa é gestão
 * (ADMIN/MANAGER); abrir/pedir-conta aceita garçom; fechar (mexe no caixa) não.
 *
 * As rotas de sessão são por MESA ({id} = mesa): o serviço resolve a comanda
 * ativa dela. O salão não precisa conhecer o id da sessão.
 */
@RestController
@RequestMapping("/tables")
class TableController(private val service: TableService) {

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER','WAITER','STAFF','KITCHEN','OPERATOR')")
    fun list(): List<TableDto> = service.listTables()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun create(@Valid @RequestBody req: TableCreateRequest): TableDto = service.createTable(req)

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun update(@PathVariable id: UUID, @Valid @RequestBody req: TableUpdateRequest): TableDto =
        service.updateTable(id, req)

    @PostMapping("/{id}/session/open")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER','WAITER')")
    fun openSession(@PathVariable id: UUID): TableDto =
        service.openSession(id, SecurityUtils.currentPrincipal()?.userId)

    @PostMapping("/{id}/session/bill")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER','WAITER')")
    fun requestBill(@PathVariable id: UUID): TableDto =
        service.requestBill(id, SecurityUtils.currentPrincipal()?.userId)

    @PostMapping("/{id}/session/close")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    fun closeSession(@PathVariable id: UUID): TableDto =
        service.closeSession(id, SecurityUtils.currentPrincipal()?.userId)
}
