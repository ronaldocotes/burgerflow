package com.menuflow.controller

import com.menuflow.dto.PaymentMethodConfigResponse
import com.menuflow.dto.PaymentMethodConfigUpsertRequest
import com.menuflow.service.PaymentMethodConfigService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Formas de pagamento configuraveis (issue #8). Sob /api/v1 (logo /config/payment-methods).
 * Leitura aberta as funcoes operacionais (o PDV precisa saber as formas ativas e o
 * repasse); escrita e gestao (ADMIN/MANAGER).
 */
@RestController
@RequestMapping("/config/payment-methods")
class PaymentMethodConfigController(private val service: PaymentMethodConfigService) {

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER','WAITER','STAFF','KITCHEN','OPERATOR')")
    fun list(): List<PaymentMethodConfigResponse> = service.list()

    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun upsert(@Valid @RequestBody req: PaymentMethodConfigUpsertRequest): PaymentMethodConfigResponse =
        service.upsert(req)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun delete(@PathVariable id: UUID) = service.delete(id)
}
