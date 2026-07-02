package com.menuflow.platform

import com.menuflow.model.control.AiUsage
import com.menuflow.repository.control.AiUsageRepository
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.YearMonth
import java.time.format.DateTimeParseException

/**
 * Painel de consumo de IA por tenant — Fase F3 do modulo platform.
 *
 * GET /admin/ai-usage?month=YYYY-MM[&page=0&size=50]
 *
 * Agrega o ledger ai_usage (banco de CONTROLE) por mes e devolve todos os tenants
 * ordenados por custo decrescente. A entidade AiUsage registra um total por
 * (tenant, mes), sem granularidade por modelo — o campo model foi omitido do DTO
 * intencionalmente para nao inventar dados que nao existem no banco.
 *
 * Paginacao: implementada manualmente (subList) pois a query e sobre o banco de
 * CONTROLE com datasource fixo — sem ambiguidade de tenant routing.
 */
@RestController
@RequestMapping("/admin/ai-usage")
@PreAuthorize("hasRole('SUPER_ADMIN')")
class PlatformAiUsageController(
    private val aiUsageRepository: AiUsageRepository,
) {

    @GetMapping
    fun listUsage(
        @RequestParam(defaultValue = "") month: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): ResponseEntity<AiUsageResponse> {
        // Valida e normaliza o mes; default = mes corrente
        val resolvedMonth = resolveMonth(month)

        val all = aiUsageRepository
            .findAllByMonthYearOrderByEstimatedCostUsdMicrosDesc(resolvedMonth)

        // Paginacao manual sobre a lista ja ordenada
        val pageSize = size.coerceIn(1, 200)
        val offset = (page.coerceAtLeast(0)) * pageSize
        val paginated = if (offset >= all.size) emptyList() else
            all.subList(offset, minOf(offset + pageSize, all.size))

        val entries = paginated.map { it.toEntry() }
        val totalCost = all.sumOf { it.estimatedCostUsdMicros }
        val totalCalls = all.sumOf { it.totalRequests }

        return ResponseEntity.ok(
            AiUsageResponse(
                month = resolvedMonth,
                entries = entries,
                totalCostUsdMicros = totalCost,
                totalCalls = totalCalls,
            ),
        )
    }

    private fun resolveMonth(raw: String): String {
        if (raw.isBlank()) return YearMonth.now().toString()
        return try {
            YearMonth.parse(raw).toString() // valida formato YYYY-MM
        } catch (e: DateTimeParseException) {
            YearMonth.now().toString() // fallback gracioso
        }
    }

    private fun AiUsage.toEntry() = AiUsageEntry(
        tenantSlug = tenantSlug,
        inputTokens = promptTokens,
        outputTokens = completionTokens,
        estimatedCostUsdMicros = estimatedCostUsdMicros,
        callCount = totalRequests,
    )
}
