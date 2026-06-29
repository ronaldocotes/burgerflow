package com.menuflow.repository.tenant

import com.menuflow.model.AiConversation
import org.springframework.data.jpa.repository.JpaRepository
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
}
