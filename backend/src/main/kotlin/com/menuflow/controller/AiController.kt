package com.menuflow.controller

import com.menuflow.dto.AiChatRequest
import com.menuflow.dto.AiChatResponse
import com.menuflow.dto.AiConversationEntry
import com.menuflow.dto.AiMetricsResponse
import com.menuflow.security.SecurityUtils
import com.menuflow.service.AiConversationService
import com.menuflow.service.AiCopilotService
import com.menuflow.service.AiMetricsService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Copiloto do dono (Fase 4.1). Sob o context-path /api/v1 (logo @RequestMapping =
 * /ai). Disponivel a qualquer usuario autenticado do restaurante — a autorizacao FINA
 * (quem pode executar acoes via ferramentas, ex.: criar cupom) e checada por papel
 * dentro do AiToolRegistry. O tenant/usuario sempre vem do principal ASSINADO (JWT),
 * nunca do corpo, garantindo o isolamento por tenant (sessionId nunca cruza bancos).
 */
@RestController
@RequestMapping("/ai")
class AiController(
    private val copilotService: AiCopilotService,
    private val conversationService: AiConversationService,
    private val metricsService: AiMetricsService,
) {

    @PostMapping("/chat")
    fun chat(@Valid @RequestBody req: AiChatRequest): AiChatResponse {
        val p = SecurityUtils.currentPrincipalOrThrow()
        return copilotService.chat(
            tenantSlug = p.tenantSlug,
            tenantUuid = p.tenantUuid,
            sessionId = req.sessionId,
            userMessage = req.message,
            actorUserId = p.userId,
            userRoles = p.roles,
        )
    }

    @GetMapping("/history")
    fun history(@RequestParam sessionId: String): List<AiConversationEntry> =
        conversationService.listSession(sessionId).map { AiConversationEntry.from(it) }

    @DeleteMapping("/history")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteHistory(@RequestParam sessionId: String) {
        conversationService.deleteSession(sessionId)
    }

    /**
     * Painel de observabilidade do Copiloto do restaurante (Fase 4.2). Restrito ao
     * ADMIN do tenant — metricas de uso/custo/saude sao informacao gerencial. O tenant
     * vem do principal assinado (JwtAuthFilter ja vinculou o TenantContext).
     */
    @GetMapping("/metrics")
    @PreAuthorize("hasRole('ADMIN')")
    fun metrics(): AiMetricsResponse = metricsService.metrics()
}
