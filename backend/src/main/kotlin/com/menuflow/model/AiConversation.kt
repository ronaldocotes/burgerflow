package com.menuflow.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Mensagem do historico do Copiloto do dono (Fase 4.1). Append-only: cada turno do
 * dialogo (user/assistant/tool) e uma linha. Vive no banco do TENANT (db-per-tenant),
 * entao nao ha coluna de escopo — o banco ja isola por restaurante, e o session_id
 * agrupa o dialogo.
 */
@Entity
@Table(name = "ai_conversations")
data class AiConversation(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "session_id", nullable = false, length = 100)
    val sessionId: String,

    /** Papel da mensagem: "user", "assistant" ou "tool". */
    @Column(name = "role", nullable = false, length = 20)
    val role: String,

    /** Texto da mensagem (pergunta do dono / resposta do assistente). Null em turno de tool puro. */
    @Column(name = "content", columnDefinition = "text")
    val content: String? = null,

    /** Nome da ferramenta executada (quando role = "tool"). */
    @Column(name = "tool_name", length = 100)
    val toolName: String? = null,

    /** Resultado (JSON) devolvido pela ferramenta ao LLM (quando role = "tool"). */
    @Column(name = "tool_result", columnDefinition = "text")
    val toolResult: String? = null,

    /** Tempo de execucao da ferramenta em ms (preenchido nas linhas role="tool"; Fase 4.2). */
    @Column(name = "latency_ms")
    val latencyMs: Int? = null,

    /** Tokens (prompt+completion) da rodada (preenchido na linha final role="assistant"; Fase 4.2). */
    @Column(name = "total_tokens")
    val totalTokens: Int? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
