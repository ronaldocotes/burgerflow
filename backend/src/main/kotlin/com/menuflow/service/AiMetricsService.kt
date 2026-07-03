package com.menuflow.service

import com.menuflow.dto.AiMetricsResponse
import com.menuflow.dto.DayMetrics
import com.menuflow.dto.ToolUsageStats
import com.menuflow.repository.control.AiUsageRepository
import com.menuflow.repository.tenant.AiConversationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Observabilidade do Copiloto (Fase 4.2) a partir do historico no banco do TENANT.
 * Requests = mensagens role='user'; tokens = soma de total_tokens nas linhas
 * role='assistant'; bloqueios = linhas role='blocked'; latencia/uso de ferramentas =
 * linhas role='tool'. O ledger de billing mensal (control.ai_usage) e outra fonte,
 * mas as metricas operacionais diarias vivem aqui (granularidade por mensagem).
 */
@Service
class AiMetricsService(
    private val repository: AiConversationRepository,
    private val aiUsageRepository: AiUsageRepository,
) {
    private val zone = ZoneId.of("America/Sao_Paulo")

    @Transactional("tenantTransactionManager", readOnly = true)
    fun metrics(tenantId: UUID): AiMetricsResponse {
        val today = LocalDate.now(zone)
        val startToday = today.atStartOfDay(zone).toInstant()
        val start7 = today.minusDays(6).atStartOfDay(zone).toInstant()

        val topTools = repository.toolUsageSince(start7).take(5).map { row ->
            ToolUsageStats(
                toolName = row[0] as String,
                callCount = (row[1] as Number).toInt(),
                avgLatencyMs = (row[2] as Number).toLong(),
            )
        }

        val monthYear = today.format(DateTimeFormatter.ofPattern("yyyy-MM"))
        val estimatedCost = aiUsageRepository.findByTenantIdAndMonthYear(tenantId, monthYear)
            ?.estimatedCostUsdMicros ?: 0L

        return AiMetricsResponse(
            today = dayMetrics(startToday),
            last7Days = dayMetrics(start7),
            topTools = topTools,
            avgLatencyMs = repository.avgToolLatencySince(start7).toLong(),
            blockedRequests = repository.countByRoleAndCreatedAtGreaterThanEqual("blocked", startToday).toInt(),
            estimatedCostUsdMicros = estimatedCost,
        )
    }

    private fun dayMetrics(from: Instant): DayMetrics {
        val requests = repository.countByRoleAndCreatedAtGreaterThanEqual("user", from).toInt()
        val tokens = repository.sumTokensSince(from).toInt()
        val avg = if (requests > 0) tokens.toDouble() / requests else 0.0
        return DayMetrics(requests = requests, tokens = tokens, avgTokensPerRequest = avg)
    }
}
