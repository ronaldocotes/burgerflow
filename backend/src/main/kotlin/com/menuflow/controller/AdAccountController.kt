package com.menuflow.controller

import com.menuflow.ads.AdAccountResponse
import com.menuflow.ads.AdAccountService
import com.menuflow.ads.ConnectAdAccountRequest
import com.menuflow.platform.ModuleKey
import com.menuflow.platform.RequiresModule
import com.menuflow.security.SecurityUtils
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Central de Trafego Pago — conexao read-only da conta Meta Ads (Fase 8.0). Sob o
 * context-path /api/v1 (logo @RequestMapping = /ads). Gate triplo:
 *   1. @PreAuthorize a nivel de classe: so ADMIN/MANAGER do tenant (gestao/verba);
 *   2. @RequiresModule(ADS): o modulo precisa estar habilitado para o tenant
 *      (entitlement no banco de CONTROLE via tenant_module, default OFF no BASIC);
 *   3. db-per-tenant: a rota ja aterrissa no banco do restaurante (isolamento).
 *
 * O token colado no POST e validado na Meta e guardado CIFRADO; nenhum endpoint
 * devolve o token (nem cifrado). Criar/pausar campanha e a Fase 8.2 (depois).
 */
@RestController
@RequestMapping("/ads")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
class AdAccountController(private val service: AdAccountService) {

    /** Conecta as contas de anuncio que o System User Token controla. */
    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresModule(ModuleKey.ADS)
    fun connect(@Valid @RequestBody req: ConnectAdAccountRequest): List<AdAccountResponse> {
        val userId = SecurityUtils.currentPrincipalOrThrow().userId
        return service.connect(req.token, userId)
    }

    /** Lista as contas conectadas do tenant (sem token). */
    @GetMapping("/accounts")
    @RequiresModule(ModuleKey.ADS)
    fun list(): List<AdAccountResponse> = service.list()

    /** Desconecta (remove) uma conta. */
    @DeleteMapping("/accounts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresModule(ModuleKey.ADS)
    fun disconnect(@PathVariable id: UUID) = service.disconnect(id)
}
