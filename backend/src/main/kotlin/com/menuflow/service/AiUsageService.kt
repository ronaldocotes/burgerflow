package com.menuflow.service

import com.menuflow.model.control.AiUsage
import com.menuflow.repository.control.AiUsageRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Registro do consumo de IA por tenant/mes no banco de CONTROLE (faturamento). Bean
 * separado do orquestrador: o controlTransactionManager (@Primary) cuida da transacao,
 * independente da transacao de tenant. Upsert por (tenant_id, month_year).
 *
 * O chamador (AiCopilotService) envolve esta chamada em try/catch: telemetria de
 * billing e best-effort e NUNCA pode derrubar a resposta do copiloto ao dono.
 */
@Service
class AiUsageService(
    private val repository: AiUsageRepository,
) {

    @Transactional("controlTransactionManager")
    fun record(
        tenantId: UUID,
        tenantSlug: String,
        monthYear: String,
        promptTokens: Long,
        completionTokens: Long,
        estimatedCostUsdMicros: Long = 0L,
    ) {
        val existing = repository.findByTenantIdAndMonthYear(tenantId, monthYear)
        if (existing != null) {
            existing.promptTokens += promptTokens
            existing.completionTokens += completionTokens
            existing.totalRequests += 1
            existing.estimatedCostUsdMicros += estimatedCostUsdMicros
            repository.save(existing)
            return
        }
        try {
            repository.save(
                AiUsage(
                    tenantId = tenantId,
                    tenantSlug = tenantSlug,
                    monthYear = monthYear,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    totalRequests = 1,
                    estimatedCostUsdMicros = estimatedCostUsdMicros,
                ),
            )
        } catch (e: DataIntegrityViolationException) {
            // Corrida: outra requisicao criou a linha do mes ao mesmo tempo. Recarrega e soma.
            val row = repository.findByTenantIdAndMonthYear(tenantId, monthYear) ?: throw e
            row.promptTokens += promptTokens
            row.completionTokens += completionTokens
            row.totalRequests += 1
            row.estimatedCostUsdMicros += estimatedCostUsdMicros
            repository.save(row)
        }
    }
}
