package com.menuflow.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.menuflow.client.ChatMessage
import com.menuflow.client.ChatResponse
import com.menuflow.client.LiteLLMClient
import com.menuflow.dto.AiChatResponse
import com.menuflow.exception.ForbiddenException
import com.menuflow.exception.TooManyRequestsException
import com.menuflow.security.ratelimit.AiTenantRateLimiter
import com.menuflow.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Copiloto do dono (Fase 4.1). Orquestra o dialogo: monta o contexto, chama o LLM via
 * [LiteLLMClient], executa as ferramentas que ele pedir ([AiToolRegistry]) e persiste o
 * historico. NAO e @Transactional — faz HTTP em loop, entao nunca segura uma transacao
 * (a persistencia vive em beans separados com transacoes curtas: [AiConversationService]
 * no tenant e [AiUsageService] no controle).
 *
 * Guardas: copiloto desligado -> 403 amigavel; limite diario -> 429 amigavel; loop de
 * tool-use limitado a [MAX_ITERATIONS]; falha do LLM -> 503 (ServiceUnavailableException
 * do LiteLLMClient) com mensagem tratavel.
 */
@Service
class AiCopilotService(
    private val liteLLMClient: LiteLLMClient,
    private val toolRegistry: AiToolRegistry,
    private val conversationService: AiConversationService,
    private val usageService: AiUsageService,
    private val tenantConfigService: TenantConfigService,
    private val aiRateLimiter: AiTenantRateLimiter,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val zone = ZoneId.of("America/Sao_Paulo")

    /**
     * Processa uma pergunta do dono e devolve a resposta final + ferramentas usadas.
     * [tenantUuid] e usado no ledger de uso (controle); [userRoles] autoriza as
     * ferramentas de acao. [sessionId] nulo/vazio -> gera um novo UUID.
     */
    fun chat(
        tenantSlug: String,
        tenantUuid: UUID,
        sessionId: String?,
        userMessage: String,
        actorUserId: UUID?,
        userRoles: List<String>,
    ): AiChatResponse {
        val previous = TenantContext.get()
        TenantContext.set(tenantSlug) // garante o roteamento das ferramentas ao banco do tenant
        try {
            val config = tenantConfigService.get()
            if (!config.aiEnabled) {
                throw ForbiddenException("O Copiloto de IA esta desativado. Ative em Configuracoes para conversar com o assistente.")
            }

            val sid = sessionId?.trim()?.ifBlank { null } ?: UUID.randomUUID().toString()

            // Rate limit por TENANT (anti-monopolio do gateway LiteLLM). Distinto do
            // rate limit por usuario; uma janela curta (default 20/min) por restaurante.
            if (!aiRateLimiter.tryAcquire(tenantSlug)) {
                throw TooManyRequestsException("Muitas solicitacoes simultaneas para este restaurante.")
            }

            // Guardrail 1 — truncamento: mensagem acima do teto e cortada SILENCIOSAMENTE
            // (nao lancamos erro; apenas logamos). Protege contexto/custo do LLM.
            val message = if (userMessage.length > config.aiMaxMessageLength) {
                log.warn("Mensagem do copiloto truncada de {} para {} chars (tenant {})", userMessage.length, config.aiMaxMessageLength, tenantSlug)
                userMessage.take(config.aiMaxMessageLength)
            } else {
                userMessage
            }

            // Guardrail 2 — prompt injection: padroes default (hardcoded) + extras do tenant.
            // Se bater, NAO chama o LLM: registra a tentativa (role='blocked') e devolve a
            // mensagem padrao de recusa com toolsUsed vazio.
            if (isBlocked(message, config.aiBlockedPatterns)) {
                log.warn("Mensagem bloqueada por guardrail de injection (tenant {})", tenantSlug)
                conversationService.save(sid, "blocked", message)
                return AiChatResponse(text = BLOCKED_MESSAGE, sessionId = sid, toolsUsed = emptyList(), tokensUsed = 0)
            }

            // Rate limit diario: perguntas (role='user') desde o inicio do dia no fuso do negocio.
            val startOfDay = LocalDate.now(zone).atStartOfDay(zone).toInstant()
            val usedToday = conversationService.countUserMessagesSince(startOfDay)
            if (usedToday >= config.aiDailyLimit) {
                throw TooManyRequestsException(
                    "Voce atingiu o limite de ${config.aiDailyLimit} perguntas por dia ao copiloto. Tente novamente amanha.",
                )
            }

            // Persiste a pergunta (ja truncada) ANTES de chamar o LLM (historico + contagem diaria).
            conversationService.save(sid, "user", message)

            // Contexto: prompt de sistema + historico recente (so texto user/assistant; os
            // turnos 'tool' sao intermediarios e nao sao reenviados entre requisicoes).
            val messages = mutableListOf(ChatMessage("system", systemPrompt(config.restaurantName, config.aiSystemPrompt)))
            conversationService.recentHistory(sid)
                .filter { it.role in setOf("user", "assistant") && !it.content.isNullOrBlank() }
                .forEach { messages.add(ChatMessage(it.role, it.content)) }

            val tools = toolRegistry.toolDefinitions()
            val toolsUsed = mutableListOf<String>()
            var promptTokens = 0L
            var completionTokens = 0L
            var finalText: String? = null
            var lastResponse: ChatResponse? = null

            // Loop de tool-use, limitado para nao laçar indefinidamente.
            for (iteration in 1..MAX_ITERATIONS) {
                val response = liteLLMClient.chat(messages, tools)
                lastResponse = response
                promptTokens += response.promptTokens
                completionTokens += response.completionTokens

                if (response.toolCalls.isEmpty()) {
                    finalText = response.content ?: ""
                    break
                }

                // O assistant pediu ferramentas: ecoa o turno e executa cada uma.
                messages.add(ChatMessage("assistant", response.content, toolCalls = response.toolCalls))
                for (call in response.toolCalls) {
                    // Tracing de latencia por ferramenta (persiste latency_ms; o log por-tool
                    // com o tenant fica dentro de AiToolRegistry.execute).
                    val start = System.currentTimeMillis()
                    val result = toolRegistry.execute(call.name, call.arguments, actorUserId, userRoles)
                    val latencyMs = (System.currentTimeMillis() - start).toInt()
                    toolsUsed.add(call.name)
                    conversationService.save(sid, "tool", content = null, toolName = call.name, toolResult = result, latencyMs = latencyMs)
                    messages.add(ChatMessage("tool", content = result, toolCallId = call.id))
                }
            }

            val text = finalText
                ?: lastResponse?.content?.takeIf { it.isNotBlank() }
                ?: "Nao consegui concluir a analise agora. Pode reformular a pergunta?"

            val totalTokens = promptTokens + completionTokens
            conversationService.save(sid, "assistant", text, totalTokens = totalTokens.toInt())

            // Telemetria de uso (billing) — best-effort: nunca derruba a resposta ao dono.
            try {
                usageService.record(tenantUuid, tenantSlug, currentMonth(), promptTokens, completionTokens)
            } catch (e: Exception) {
                log.warn("Falha ao registrar uso de IA (ignorado): {}", e.message)
            }

            return AiChatResponse(text = text, sessionId = sid, toolsUsed = toolsUsed.distinct(), tokensUsed = totalTokens)
        } finally {
            if (previous != null) TenantContext.set(previous) else TenantContext.clear()
        }
    }

    /**
     * Decide se a mensagem deve ser bloqueada por prompt injection: bate contra os
     * padroes default (hardcoded) E os extras configurados pelo tenant (JSON array de
     * regexes). Regex invalida nos extras e ignorada (nunca derruba o fluxo).
     */
    private fun isBlocked(message: String, extraPatternsJson: String?): Boolean {
        if (DEFAULT_BLOCKED_PATTERNS.any { it.containsMatchIn(message) }) return true
        return parseExtraPatterns(extraPatternsJson).any {
            runCatching { it.containsMatchIn(message) }.getOrDefault(false)
        }
    }

    /** Le o JSON array de regexes extras do tenant; null/vazio/invalido -> lista vazia. */
    private fun parseExtraPatterns(json: String?): List<Regex> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            objectMapper.readValue(json, Array<String>::class.java)
                .mapNotNull { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }
        } catch (e: Exception) {
            log.warn("ai_blocked_patterns invalido (ignorado): {}", e.message)
            emptyList()
        }
    }

    private fun currentMonth(): String = LocalDate.now(zone).format(MONTH_FMT)

    private fun systemPrompt(restaurantName: String?, custom: String?): String {
        val name = restaurantName?.takeIf { it.isNotBlank() } ?: "seu restaurante"
        val today = LocalDate.now(zone).toString()
        val base = """
            Voce e o copiloto de negocios do restaurante $name.
            Responda SEMPRE em portugues brasileiro, de forma direta e pratica.
            Use as ferramentas disponiveis para consultar dados reais do restaurante antes de afirmar numeros.
            Nunca invente valores: se nao tiver o dado, use a ferramenta apropriada ou diga que nao sabe.
            Nao execute acoes destrutivas. Voce so atua neste restaurante; nunca acesse dados de outros.
            Data de hoje: $today.
        """.trimIndent()
        return if (custom.isNullOrBlank()) base else "$base\n\nInstrucoes do dono:\n${custom.trim()}"
    }

    companion object {
        /** Teto de rodadas de tool-use por pergunta (guarda contra loop infinito). */
        const val MAX_ITERATIONS = 5
        private val MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM")

        /** Resposta padrao quando a mensagem e barrada por guardrail de injection. */
        const val BLOCKED_MESSAGE = "Não consigo responder a esse tipo de solicitação."

        /**
         * Padroes de prompt injection bloqueados por padrao (sempre ativos; o tenant
         * so ACRESCENTA via ai_blocked_patterns). Cobrem jailbreak classico, injecao
         * de role, extracao de system prompt e escalonamento.
         */
        val DEFAULT_BLOCKED_PATTERNS: List<Regex> = listOf(
            Regex("(?i)ignore.*(previous|above|instruc)"),
            Regex("(?i)(system|assistant)\\s*:"),
            Regex("(?i)reveal.*(prompt|instruc|system)"),
            Regex("(?i)(sudo|root|admin)\\s+(mode|access)"),
        )
    }
}
