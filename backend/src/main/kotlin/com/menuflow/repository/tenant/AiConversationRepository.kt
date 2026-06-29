package com.menuflow.repository.tenant

import com.menuflow.model.AiConversation
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * Historico do Copiloto no banco do TENANT (db-per-tenant ja isola por restaurante).
 */
@Repository
interface AiConversationRepository : JpaRepository<AiConversation, UUID> {

    /** Historico completo de uma sessao em ordem cronologica (GET /ai/history). */
    fun findBySessionIdOrderByCreatedAtAsc(sessionId: String): List<AiConversation>

    /** Ultimas 10 mensagens da sessao (mais recentes primeiro) para montar o contexto do LLM. */
    fun findTop10BySessionIdOrderByCreatedAtDesc(sessionId: String): List<AiConversation>

    /** Rate limit diario: quantas perguntas (role='user') desde o inicio do dia. */
    fun countByRoleAndCreatedAtGreaterThanEqual(role: String, from: Instant): Long

    /** Apaga o historico de uma sessao (DELETE /ai/history). */
    fun deleteBySessionId(sessionId: String): Long

    // --- Observabilidade (GET /ai/metrics, Fase 4.2) ---

    /** Soma de tokens (linhas role='assistant') desde [from] — consumo do periodo. */
    @Query("SELECT COALESCE(SUM(c.totalTokens), 0) FROM AiConversation c WHERE c.role = 'assistant' AND c.createdAt >= :from")
    fun sumTokensSince(@Param("from") from: Instant): Long

    /** Uso de ferramentas desde [from]: [toolName, callCount, avgLatencyMs], mais usadas primeiro. */
    @Query(
        "SELECT c.toolName, COUNT(c), COALESCE(AVG(c.latencyMs), 0) FROM AiConversation c " +
            "WHERE c.role = 'tool' AND c.toolName IS NOT NULL AND c.createdAt >= :from " +
            "GROUP BY c.toolName ORDER BY COUNT(c) DESC",
    )
    fun toolUsageSince(@Param("from") from: Instant): List<Array<Any>>

    /** Latencia media (ms) de TODAS as execucoes de ferramenta desde [from]. */
    @Query("SELECT COALESCE(AVG(c.latencyMs), 0) FROM AiConversation c WHERE c.role = 'tool' AND c.latencyMs IS NOT NULL AND c.createdAt >= :from")
    fun avgToolLatencySince(@Param("from") from: Instant): Double
}
