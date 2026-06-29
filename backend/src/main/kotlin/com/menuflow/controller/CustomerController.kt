package com.menuflow.controller

import com.menuflow.service.CampaignService
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Opt-in/opt-out de marketing por cliente (Fase 3.4). Sob o context-path /api/v1.
 * Operacao de balcao (ADMIN/MANAGER/CASHIER): o caixa registra o consentimento do
 * cliente. O opt-out do PROPRIO cliente (link de descadastro) e publico — ver
 * PublicMenuController.optOut. db-per-tenant: a rota ja escopa pelo banco do tenant.
 */
@RestController
@RequestMapping("/customers/{customerId}")
class CustomerController(private val campaignService: CampaignService) {

    @PostMapping("/opt-in")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun optIn(@PathVariable customerId: UUID) = campaignService.grantOptIn(customerId)

    @PostMapping("/opt-out")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun optOut(@PathVariable customerId: UUID) = campaignService.revokeOptInById(customerId)
}
