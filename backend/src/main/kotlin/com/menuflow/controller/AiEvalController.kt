package com.menuflow.controller

import com.menuflow.dto.EvalSummary
import com.menuflow.dto.GoldenQuestionResponse
import com.menuflow.service.AiEvalService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Avaliacao (eval) do Copiloto contra o golden set (Fase 4.2). Operacao de PLATAFORMA,
 * cross-tenant: gated a SUPER_ADMIN (igual ao TenantMigrationAdminController). Fail-closed
 * ate a camada de auth emitir SUPER_ADMIN no JWT — o que e o default seguro para uma
 * visao cross-tenant. O golden set e a referencia global; o eval roda contra um tenant
 * informado por parametro.
 */
@RestController
@RequestMapping("/ai")
class AiEvalController(
    private val evalService: AiEvalService,
) {

    @GetMapping("/golden-set")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    fun goldenSet(): List<GoldenQuestionResponse> = evalService.goldenSet()

    @PostMapping("/eval")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    fun eval(@RequestParam tenantSlug: String): EvalSummary = evalService.runEval(tenantSlug)
}
