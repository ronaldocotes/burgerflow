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
