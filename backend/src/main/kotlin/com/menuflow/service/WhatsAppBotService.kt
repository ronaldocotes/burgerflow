package com.menuflow.service

import com.menuflow.client.ChatMessage
import com.menuflow.client.ChatResponse
import com.menuflow.client.LiteLLMClient
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.model.BotHandoff
import com.menuflow.model.TenantConfig
import com.menuflow.repository.tenant.BotHandoffRepository
import com.menuflow.repository.tenant.TenantConfigRepository
import com.menuflow.security.ratelimit.AiTenantRateLimiter
import com.menuflow.security.ratelimit.WebhookMessageDeduplicator
import com.menuflow.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/**
 * Bot WhatsApp INBOUND (Fase 4.3): o cliente manda mensagem ao numero do restaurante
 * (via WAHA) e o assistente virtual responde — consultando cardapio, status do proprio
 * pedido e horario. NUNCA executa acoes e NUNCA acessa dados do dono ou de outros
 * clientes (ferramentas restritas em [BotToolRegistry]).
 *
 * DECISAO DE PROJETO (desvio justificado do spec, por SEGURANCA): o bot NAO reusa
 * [AiCopilotService.chat], que expoe as ferramentas do DONO (DRE/RFV/faturamento/criar
 * cupom). Um cliente anonimo no WhatsApp jamais pode alcancar essas ferramentas. O bot
 * roda seu PROPRIO loop de tool-use com o registry restrito [BotToolRegistry].
 *
 * Fluxo (handleIncoming):
 *  1. idempotencia por messageId (WAHA reentrega ate receber 2xx);
 *  2. bot desligado -> silencio;
 *  3. handoff humano ativo -> bot CALADO + notifica o dono;
 *  4. palavra-chave de handoff -> cria handoff, responde, notifica;
 *  5. guardrail de injection (mesmos padroes do copiloto, mensagem do cliente = dado
 *     NAO-confiavel) -> recusa sem chamar o LLM;
 *  6. rate-limit por tenant;
 *  7. loop LLM com ferramentas restritas -> envia a resposta ao cliente.
 *
 * Tudo e fail-open: qualquer falha e logada e engolida (o webhook sempre devolve 200;
 * uma mensagem perdida e melhor que reentrega agressiva ou erro 5xx ao WAHA).
 */
@Service
class WhatsAppBotService(
    private val tenantConfigRepository: TenantConfigRepository,
    private val botHandoffRepository: BotHandoffRepository,
    private val conversationService: AiConversationService,
    private val liteLLMClient: LiteLLMClient,
    private val botToolRegistry: BotToolRegistry,
    private val whatsAppService: WhatsAppService,
    private val aiRateLimiter: AiTenantRateLimiter,
    private val deduplicator: WebhookMessageDeduplicator,
    @Value("\${menuflow.app.base-url:}") private val appBaseUrl: String,
    @Qualifier("tenantTransactionManager") txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val txTemplate = TransactionTemplate(txManager)

    /**
     * Ponto de entrada ASSINCRONO chamado pelo webhook. Roda fora do thread HTTP para o
     * WAHA receber 200 imediatamente. Em db-per-tenant o thread async perde o
     * TenantContext, mas [handleIncoming] o vincula a partir do slug recebido.
     */
    @Async
    fun handleIncomingAsync(tenantSlug: String, from: String, body: String, messageId: String) {
        handleIncoming(tenantSlug, from, body, messageId)
    }

    /**
     * Processa uma mensagem inbound (sincrono e testavel). Vincula o TenantContext do
     * slug recebido (restaura no fim). Nunca lanca: falha e logada (fail-open).
     */
    fun handleIncoming(tenantSlug: String, from: String, body: String, messageId: String) {
        val previous = TenantContext.get()
        TenantContext.set(tenantSlug)
        try {
            doHandle(tenantSlug, from, body, messageId)
        } catch (e: Exception) {
            log.error("Falha ao processar mensagem do bot (tenant {}): {}", tenantSlug, e.message)
        } finally {
            if (previous != null) TenantContext.set(previous) else TenantContext.clear()
        }
    }

    private fun doHandle(tenantSlug: String, from: String, body: String, messageId: String) {
        // 1. Idempotencia: a mesma mensagem (reentrega do WAHA) processa uma unica vez.
        if (messageId.isNotBlank() && !deduplicator.markIfNew("$tenantSlug:$messageId")) {
            log.debug("Mensagem {} ja processada (tenant {}); ignorando", messageId, tenantSlug)
            return
        }

        val phone = normalizePhone(from)
        if (phone.isBlank()) return

        // 2. Bot desligado -> silencio absoluto.
        val config = txTemplate.execute { tenantConfigRepository.findFirstByOrderByCreatedAtAsc() }
        if (config == null || !config.botEnabled) return
        val session = config.wahaPrimaryPhone

        // 3. Handoff humano ATIVO -> o bot nao responde; apenas avisa o dono.
        val activeHandoff = txTemplate.execute {
            botHandoffRepository.existsByCustomerPhoneAndResolvedFalse(phone)
        } ?: false
        if (activeHandoff) {
            notifyOwner(config, "📨 Nova mensagem de $phone: $body")
            return
        }

        // 4. Palavra-chave de transferencia -> cria handoff, responde e notifica.
        val keyword = handoffKeyword(config)
        if (body.contains(keyword, ignoreCase = true)) {
            txTemplate.execute {
                botHandoffRepository.save(BotHandoff(customerPhone = phone, lastBotMessage = body))
            }
            val handoffMsg = config.botHandoffMessage?.trim()?.ifBlank { null } ?: DEFAULT_HANDOFF_MESSAGE
            whatsAppService.sendCampaign(phone, handoffMsg, session)
            notifyOwner(config, "🙋 Cliente $phone pediu atendente humano: $body")
            return
        }

        // 5. Guardrail de injection indireta: a mensagem do cliente e dado NAO-confiavel.
        // Usa os MESMOS padroes default do copiloto (Fase 4.2). Bloqueado -> recusa SEM LLM.
        if (isBlocked(body)) {
            log.warn("Mensagem de cliente bloqueada por guardrail (tenant {})", tenantSlug)
            conversationService.save(sessionIdOf(tenantSlug, phone), "bot_blocked", body)
            whatsAppService.sendCampaign(phone, BOT_BLOCKED_MESSAGE, session)
            return
        }

        // 6. Rate-limit por TENANT (reusa o limiter do copiloto). Estourou -> ignora
        // silenciosamente (nao spamma o cliente com erro).
        if (!aiRateLimiter.tryAcquire(tenantSlug)) {
            log.warn("Rate-limit do bot atingido (tenant {}); mensagem ignorada", tenantSlug)
            return
        }

        // 7. Conversa com o LLM (ferramentas restritas) e envia a resposta.
        val reply = runConversation(sessionIdOf(tenantSlug, phone), config, tenantSlug, phone, body)
        whatsAppService.sendCampaign(phone, reply, session)
    }

    /**
     * Loop de tool-use proprio do bot (espelha o do copiloto, mas com [BotToolRegistry]
     * read-only). A persistencia usa roles PREFIXADAS com "bot_" para NAO poluir a
     * contagem diaria nem as metricas do copiloto do dono (que filtram role='user').
     * As ferramentas recebem o telefone VERIFICADO do remetente, nunca um do LLM.
     */
    private fun runConversation(sid: String, config: TenantConfig, tenantSlug: String, phone: String, body: String): String {
        conversationService.save(sid, "bot_user", body)

        val messages = mutableListOf(ChatMessage("system", systemPrompt(config, tenantSlug)))
        conversationService.recentHistory(sid)
            .filter { it.role in setOf("bot_user", "bot_assistant") && !it.content.isNullOrBlank() }
            .forEach { messages.add(ChatMessage(if (it.role == "bot_assistant") "assistant" else "user", it.content)) }

        val tools = botToolRegistry.toolDefinitions()
        var finalText: String? = null
        var lastResponse: ChatResponse? = null

        for (iteration in 1..MAX_ITERATIONS) {
            val response = liteLLMClient.chat(messages, tools)
            lastResponse = response
            if (response.toolCalls.isEmpty()) {
                finalText = response.content ?: ""
                break
            }
            messages.add(ChatMessage("assistant", response.content, toolCalls = response.toolCalls))
            for (call in response.toolCalls) {
                val result = botToolRegistry.execute(call.name, phone)
                conversationService.save(sid, "bot_tool", content = null, toolName = call.name, toolResult = result)
                messages.add(ChatMessage("tool", content = result, toolCallId = call.id))
            }
        }

        val text = finalText
            ?: lastResponse?.content?.takeIf { it.isNotBlank() }
            ?: "Desculpe, nao consegui responder agora. Digite \"${handoffKeyword(config)}\" para falar com um atendente."
        conversationService.save(sid, "bot_assistant", text)
        return text
    }

    /**
     * Marca um handoff como resolvido (atendente encerrou) e avisa o cliente
     * (best-effort, fora da transacao). Chamado pelo endpoint admin.
     */
    fun resolveHandoff(handoffId: UUID) {
        val handoff = txTemplate.execute {
            botHandoffRepository.findById(handoffId).orElse(null)?.also {
                it.resolved = true
                it.resolvedAt = Instant.now()
                botHandoffRepository.save(it)
            }
        } ?: throw ResourceNotFoundException("Handoff nao encontrado")

        val session = txTemplate.execute { tenantConfigRepository.findFirstByOrderByCreatedAtAsc()?.wahaPrimaryPhone }
        // Aviso opcional ao cliente (fail-open: nunca derruba o encerramento).
        whatsAppService.sendCampaign(handoff.customerPhone, "Atendimento encerrado. Obrigado!", session)
    }

    /** Lista handoffs por situacao (resolved=false = pendentes), paginado. */
    fun listHandoffs(resolved: Boolean, pageable: Pageable): Page<BotHandoff> =
        txTemplate.execute { botHandoffRepository.findByResolvedOrderByCreatedAtDesc(resolved, pageable) }!!

    // ----------------------------- Helpers -----------------------------

    private fun notifyOwner(config: TenantConfig, text: String) {
        // Aviso ao dono/atendente. O numero do dono e tratado como o numero do
        // restaurante (wahaPrimaryPhone). Roteamento exato e infra do WAHA (FOLLOW-UP:
        // numero de notificacao dedicado por tenant). Best-effort: sendCampaign nunca lanca.
        val owner = config.wahaPrimaryPhone?.trim()?.ifBlank { null } ?: return
        whatsAppService.sendCampaign(owner, text, null)
    }

    /** "5511999999999@c.us" -> "5511999999999" (so digitos). */
    private fun normalizePhone(from: String): String =
        from.substringBefore("@").replace(Regex("[^0-9]"), "")

    private fun handoffKeyword(config: TenantConfig): String =
        config.botHandoffKeyword?.trim()?.ifBlank { null } ?: DEFAULT_KEYWORD

    private fun sessionIdOf(tenantSlug: String, phone: String): String = "bot:$tenantSlug:$phone"

    private fun isBlocked(message: String): Boolean =
        AiCopilotService.DEFAULT_BLOCKED_PATTERNS.any { it.containsMatchIn(message) }

    /** Prompt de sistema do BOT (cliente). Distinto do copiloto do dono. */
    private fun systemPrompt(config: TenantConfig, tenantSlug: String): String {
        val name = config.restaurantName?.takeIf { it.isNotBlank() } ?: "o restaurante"
        val keyword = handoffKeyword(config)
        // G7: link do cardapio injetado no prompt quando APP_BASE_URL esta configurado.
        val menuLink = appBaseUrl.trim().trimEnd('/')
            .takeIf { it.isNotBlank() }
            ?.let { "\nO link do cardapio deste restaurante e: $it/cardapio/$tenantSlug — envie este link quando o cliente quiser ver o cardapio ou fazer um pedido online." }
            ?: ""
        val base = """
            Voce e o atendente virtual do restaurante $name.
            Responda SEMPRE em portugues brasileiro, de forma amigavel e concisa.
            Voce pode: verificar o status de pedidos do cliente, informar o cardapio e informar os horarios.
            Voce NAO pode: fazer pedidos, alterar pedidos nem acessar dados de outros clientes.
            Use as ferramentas para consultar dados reais antes de afirmar; nunca invente precos ou status.
            Se o cliente quiser falar com um humano, oriente a digitar "$keyword".
            Limite suas respostas a no maximo 3 paragrafos curtos (WhatsApp tem limite de atencao).${menuLink}
        """.trimIndent()
        val custom = config.botSystemPrompt?.trim()?.ifBlank { null }
        return if (custom == null) base else "$base\n\nInstrucoes do restaurante:\n$custom"
    }

    companion object {
        /** Teto de rodadas de tool-use por mensagem (guarda contra loop). */
        const val MAX_ITERATIONS = 5

        /** Palavra-chave default de transferencia para atendente humano. */
        const val DEFAULT_KEYWORD = "atendente"

        /** Resposta quando a mensagem do cliente e barrada pelo guardrail de injection. */
        const val BOT_BLOCKED_MESSAGE = "Nao consigo processar essa mensagem."

        const val DEFAULT_HANDOFF_MESSAGE = "Transferindo para um atendente humano. Aguarde!"
    }
}
