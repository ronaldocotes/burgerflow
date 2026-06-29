package com.menuflow.service

import com.menuflow.model.AiConversation
import com.menuflow.repository.tenant.AiConversationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Persistencia do historico do Copiloto no banco do TENANT. Fica em um bean SEPARADO
 * do AiCopilotService de proposito: o orquestrador (chat) NAO e transacional (faz HTTP
 * em loop), entao chamar um metodo @Transactional do proprio bean nao passaria pelo
 * proxy (self-invocation). Aqui cada gravacao/leitura e uma transacao curta no
 * tenantTransactionManager.
 */
@Service
class AiConversationService(
    private val repository: AiConversationRepository,
) {

    @Transactional("tenantTransactionManager")
    fun save(sessionId: String, role: String, content: String?, toolName: String? = null, toolResult: String? = null): AiConversation =
        repository.save(
            AiConversation(
                sessionId = sessionId,
                role = role,
                content = content,
                toolName = toolName,
                toolResult = toolResult,
            ),
        )

    /** Ultimas 10 mensagens da sessao em ordem CRONOLOGICA (para montar o contexto do LLM). */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun recentHistory(sessionId: String): List<AiConversation> =
        repository.findTop10BySessionIdOrderByCreatedAtDesc(sessionId).reversed()

    /** Quantas perguntas do dono (role='user') desde [from] — base do rate limit diario. */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun countUserMessagesSince(from: Instant): Long =
        repository.countByRoleAndCreatedAtGreaterThanEqual("user", from)

    @Transactional("tenantTransactionManager", readOnly = true)
    fun listSession(sessionId: String): List<AiConversation> =
        repository.findBySessionIdOrderByCreatedAtAsc(sessionId)

    @Transactional("tenantTransactionManager")
    fun deleteSession(sessionId: String): Long =
        repository.deleteBySessionId(sessionId)
}
