package com.menuflow.dto

import com.menuflow.model.AiConversation
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/**
 * Pergunta do dono ao Copiloto (POST /ai/chat). sessionId opcional: o frontend gera um
 * UUID por conversa; se omitido, o backend gera um e devolve para o cliente reusar.
 */
data class AiChatRequest(
    @field:NotBlank @field:Size(max = 4000) val message: String,
    @field:Size(max = 100) val sessionId: String? = null,
)

/**
 * Resposta do Copiloto. [text] e a resposta final em linguagem natural; [toolsUsed]
 * lista as ferramentas que o assistente acionou para responder (transparencia).
 */
data class AiChatResponse(
    val text: String,
    val sessionId: String,
    val toolsUsed: List<String>,
    /** Tokens (prompt+completion) consumidos na resposta. Aditivo (Fase 4.2); 0 quando bloqueado. */
    val tokensUsed: Long = 0,
)

// ----------------------------- Observabilidade (Fase 4.2) -----------------------------

/** Metricas de um intervalo (hoje / ultimos 7 dias). */
data class DayMetrics(
    val requests: Int,
    val tokens: Int,
    val avgTokensPerRequest: Double,
)

/** Uso de uma ferramenta no periodo (top tools). */
data class ToolUsageStats(
    val toolName: String,
    val callCount: Int,
    val avgLatencyMs: Long,
)

/** Painel de observabilidade do Copiloto (GET /ai/metrics, ADMIN do restaurante). */
data class AiMetricsResponse(
    val today: DayMetrics,
    val last7Days: DayMetrics,
    val topTools: List<ToolUsageStats>,
    val avgLatencyMs: Long,
    val blockedRequests: Int,
    /** Custo estimado acumulado no mes corrente em microdolares (1 USD = 1_000_000). */
    val estimatedCostUsdMicros: Long = 0L,
)

// ----------------------------- Avaliacao / golden set (Fase 4.2) -----------------------------

/** Pergunta do golden set (GET /ai/golden-set). */
data class GoldenQuestionResponse(
    val id: UUID,
    val question: String,
    val expectedTools: List<String>,
    val category: String,
    val active: Boolean,
)

/** Resultado da avaliacao de UMA pergunta contra o tenant. */
data class EvalResult(
    val question: String,
    val expectedTools: List<String>,
    val actualTools: List<String>,
    val passed: Boolean,
    val latencyMs: Long,
    val tokensUsed: Long,
)

/** Sumario do eval do golden set (POST /ai/eval). */
data class EvalSummary(
    val totalQuestions: Int,
    val passed: Int,
    val passRate: Double,
    val results: List<EvalResult>,
)

/** Entrada do historico de uma sessao (GET /ai/history). */
data class AiConversationEntry(
    val id: UUID,
    val role: String,
    val content: String?,
    val toolName: String?,
    val createdAt: Instant,
) {
    companion object {
        fun from(c: AiConversation) = AiConversationEntry(
            id = c.id!!,
            role = c.role,
            content = c.content,
            toolName = c.toolName,
            createdAt = c.createdAt,
        )
    }
}
