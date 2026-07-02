package com.menuflow.platform

import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Painel super-admin — saúde das integrações externas da plataforma.
 *
 * GET /admin/integrations/health
 *   Cache servidor de 30s (gerenciado em [PlatformIntegrationsHealthService]).
 *   Resposta: { updatedAt, cards: [{ name, status, detail? }] }
 *   Status possíveis por card: OK | DEGRADED | DOWN.
 *
 * Fail-open por card: a falha em verificar uma integração retorna DOWN naquele
 * card mas não afeta os demais (exceções capturadas individualmente no service).
 *
 * Gate DUPLO (path-level SecurityConfig + @PreAuthorize). IDOR N/A: o endpoint
 * não recebe parâmetros de tenant — retorna visão agregada da plataforma.
 */
@RestController
@RequestMapping("/admin/integrations")
@PreAuthorize("hasRole('SUPER_ADMIN')")
class PlatformIntegrationsController(
    private val healthService: PlatformIntegrationsHealthService,
) {
    @GetMapping("/health")
    fun health(): IntegrationsHealthResponse = healthService.health()
}
