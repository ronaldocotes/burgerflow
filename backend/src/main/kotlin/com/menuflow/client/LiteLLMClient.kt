package com.menuflow.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.menuflow.exception.ServiceUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Mensagem no formato OpenAI (chat/completions). Espelha os papeis system/user/
 * assistant/tool. Em um turno de tool-use o assistant carrega [toolCalls]; a resposta
 * da ferramenta volta como uma mensagem role="tool" com [toolCallId] + [content].
 */
data class ChatMessage(
    val role: String,
    val content: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCall>? = null,
) {
    /** Serializa para o corpo OpenAI. content sempre presente (pode ser null no assistant com tool_calls). */
    fun toMap(): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("role" to role)
        toolCallId?.let { m["tool_call_id"] = it }
        m["content"] = content
        if (!toolCalls.isNullOrEmpty()) {
            m["tool_calls"] = toolCalls.map { tc ->
                mapOf(
                    "id" to tc.id,
                    "type" to "function",
                    // arguments e uma STRING JSON no protocolo OpenAI.
                    "function" to mapOf("name" to tc.name, "arguments" to tc.argumentsJson),
                )
            }
        }
        return m
    }
}

/**
 * Chamada de ferramenta solicitada pelo LLM. [arguments] e o JSON ja parseado (para o
 * registry executar) e [argumentsJson] e a string crua (para reenviar o turno ao LLM).
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, Any?>,
    val argumentsJson: String,
)

/** Definicao de uma ferramenta no formato OpenAI tools[] (type=function). */
data class AiTool(val definition: Map<String, Any?>)

/** Resposta normalizada do LLM: texto final OU pedidos de ferramenta + uso de tokens. */
data class ChatResponse(
    val content: String?,
    val toolCalls: List<ToolCall>,
    val model: String?,
    val promptTokens: Long,
    val completionTokens: Long,
)

/**
 * Cliente do gateway LiteLLM (OpenAI-compatible) self-hosted na A1. Unifica
 * Gemini/Grok/Claude/Mistral atras de POST /chat/completions. Mesmo padrao de HTTP
 * client do codebase (RestClient.Builder, como AsaasClient/WhatsAppService/Conversion):
 *
 *  - Default inline no @Value porque o application.yml de teste SOMBREIA o main (o
 *    bloco litellm: nao existe la); sem default o contexto de teste falharia ao
 *    resolver o placeholder. Nos testes o bean e mockado (nao bate na rede).
 *  - Timeout (connect/read) configuravel (default 30s): alvo lento nunca prende a
 *    thread HTTP indefinidamente. Falha de rede vira ServiceUnavailableException (503),
 *    tratada no copiloto como erro amigavel ao dono.
 *  - A chave (se houver) vai como Bearer; nunca e logada.
 */
@Component
class LiteLLMClient(
    @Value("\${litellm.url:http://127.0.0.1:4000}") private val url: String,
    @Value("\${litellm.key:}") private val key: String,
    @Value("\${litellm.model:mistral-small-latest}") private val defaultModel: String,
    @Value("\${litellm.timeout-ms:30000}") timeoutMs: Int,
    private val objectMapper: ObjectMapper,
    builder: RestClient.Builder,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val client: RestClient = builder
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(timeoutMs)
                setReadTimeout(timeoutMs)
            },
        )
        .build()

    /**
     * Uma rodada de chat. Envia o historico [messages] + as [tools] disponiveis e
     * devolve o texto final OU os tool_calls que o LLM quer executar. Qualquer falha
     * (rede/timeout/HTTP) vira ServiceUnavailableException — o copiloto converte em
     * mensagem amigavel.
     */
    fun chat(messages: List<ChatMessage>, tools: List<AiTool>?, maxTokens: Int = 1024): ChatResponse {
        val body = buildMap<String, Any?> {
            put("model", defaultModel)
            put("messages", messages.map { it.toMap() })
            put("max_tokens", maxTokens)
            put("temperature", 0.3)
            if (!tools.isNullOrEmpty()) {
                put("tools", tools.map { it.definition })
                put("tool_choice", "auto")
            }
        }

        val raw: Map<*, *> = try {
            client.post()
                .uri("$url/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .headers { h -> if (key.isNotBlank()) h.setBearerAuth(key) }
                .body(body)
                .retrieve()
                .body(Map::class.java)
                ?: throw ServiceUnavailableException("LiteLLM: resposta vazia")
        } catch (e: ServiceUnavailableException) {
            throw e
        } catch (e: Exception) {
            log.warn("LiteLLM indisponivel: {}", e.message)
            throw ServiceUnavailableException("Assistente de IA temporariamente indisponivel")
        }

        return parse(raw)
    }

    /** Extrai content/tool_calls/usage do JSON OpenAI de forma defensiva (sem NPE). */
    private fun parse(raw: Map<*, *>): ChatResponse {
        val choices = raw["choices"] as? List<*>
        val message = (choices?.firstOrNull() as? Map<*, *>)?.get("message") as? Map<*, *>
        val content = message?.get("content") as? String

        val toolCalls = (message?.get("tool_calls") as? List<*>).orEmpty().mapNotNull { tcAny ->
            val tc = tcAny as? Map<*, *> ?: return@mapNotNull null
            val fn = tc["function"] as? Map<*, *> ?: return@mapNotNull null
            val name = fn["name"] as? String ?: return@mapNotNull null
            val argsJson = (fn["arguments"] as? String).orEmpty().ifBlank { "{}" }
            val args: Map<String, Any?> = try {
                @Suppress("UNCHECKED_CAST")
                objectMapper.readValue(argsJson, Map::class.java) as Map<String, Any?>
            } catch (e: Exception) {
                emptyMap()
            }
            ToolCall(
                id = tc["id"] as? String ?: "call_${name}",
                name = name,
                arguments = args,
                argumentsJson = argsJson,
            )
        }

        val usage = raw["usage"] as? Map<*, *>
        return ChatResponse(
            content = content,
            toolCalls = toolCalls,
            model = raw["model"] as? String,
            promptTokens = (usage?.get("prompt_tokens") as? Number)?.toLong() ?: 0L,
            completionTokens = (usage?.get("completion_tokens") as? Number)?.toLong() ?: 0L,
        )
    }
}
