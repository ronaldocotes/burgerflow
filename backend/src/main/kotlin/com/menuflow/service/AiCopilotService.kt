package com.menuflow.service

import com.menuflow.client.ChatMessage
import com.menuflow.client.ChatResponse
import com.menuflow.client.LiteLLMClient
import com.menuflow.dto.AiChatResponse
import com.menuflow.exception.ForbiddenException
import com.menuflow.exception.TooManyRequestsException
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

            // Rate limit diario: perguntas (role='user') desde o inicio do dia no fuso do negocio.
            val startOfDay = LocalDate.now(zone).atStartOfDay(zone).toInstant()
            val usedToday = conversationService.countUserMessagesSince(startOfDay)
            if (usedToday >= config.aiDailyLimit) {
                throw TooManyRequestsException(
                    "Voce atingiu o limite de ${config.aiDailyLimit} perguntas por dia ao copiloto. Tente novamente amanha.",
                )
            }

            // Persiste a pergunta ANTES de chamar o LLM (entra no historico e na contagem diaria).
            conversationService.save(sid, "user", userMessage)

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
                    val result = toolRegistry.execute(call.name, call.arguments, actorUserId, userRoles)
                    toolsUsed.add(call.name)
                    conversationService.save(sid, "tool", content = null, toolName = call.name, toolResult = result)
                    messages.add(ChatMessage("tool", content = result, toolCallId = call.id))
                }
            }

            val text = finalText
                ?: lastResponse?.content?.takeIf { it.isNotBlank() }
                ?: "Nao consegui concluir a analise agora. Pode reformular a pergunta?"

            conversationService.save(sid, "assistant", text)

            // Telemetria de uso (billing) — best-effort: nunca derruba a resposta ao dono.
            try {
                usageService.record(tenantUuid, tenantSlug, currentMonth(), promptTokens, completionTokens)
            } catch (e: Exception) {
                log.warn("Falha ao registrar uso de IA (ignorado): {}", e.message)
            }

            return AiChatResponse(text = text, sessionId = sid, toolsUsed = toolsUsed.distinct())
        } finally {
            if (previous != null) TenantContext.set(previous) else TenantContext.clear()
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
    }
}
