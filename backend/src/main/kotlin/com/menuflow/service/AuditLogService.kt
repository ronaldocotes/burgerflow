package com.menuflow.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.menuflow.dto.AuditLogResponse
import com.menuflow.model.AuditLog
import com.menuflow.repository.tenant.AuditLogRepository
import com.menuflow.security.SecurityUtils
import jakarta.servlet.http.HttpServletRequest
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.util.UUID

/**
 * Auditoria explícita (não-AOP): o serviço de negócio chama log() DENTRO da própria
 * transação. Grava no banco do TENANT (tabela audit_log).
 *
 * Propagação REQUIRED (não MANDATORY): quando o chamador já está numa transação de
 * tenant (ex.: CashSessionService/OrderService — tenantTransactionManager) o log
 * ENTRA na mesma transação e é atômico com a mudança (rollback junto). Quando o
 * chamador está numa transação de CONTROLE (módulo de usuários), basta vincular o
 * TenantContext (withTenant) que o REQUIRED abre uma transação de tenant para gravar.
 *
 * Ator: vem do parâmetro explícito (serviços que já têm actorId, como o caixa) OU do
 * principal autenticado. Sem ator resolvível, NÃO audita (retorna em silêncio) — a
 * auditoria nunca pode derrubar o fluxo de negócio nem exigir SecurityContext em
 * chamadas service-level (testes).
 */
@Service
class AuditLogService(
    private val repo: AuditLogRepository,
    private val objectMapper: ObjectMapper,
) {

    @Transactional("tenantTransactionManager", propagation = Propagation.REQUIRED)
    fun log(
        action: String,
        entity: String,
        entityId: UUID? = null,
        before: Any? = null,
        after: Any? = null,
        reason: String? = null,
        actorUserId: UUID? = null,
        actorRole: String? = null,
    ) {
        val principal = SecurityUtils.currentPrincipal()
        val actor = actorUserId ?: principal?.userId ?: return
        val role = actorRole ?: principal?.roles?.firstOrNull()
        val request = currentRequest()
        repo.save(
            AuditLog(
                actorUserId = actor,
                actorRole = role,
                action = action,
                entity = entity,
                entityId = entityId,
                beforeJson = before?.let { objectMapper.writeValueAsString(it) },
                afterJson = after?.let { objectMapper.writeValueAsString(it) },
                reason = reason,
                ip = request?.remoteAddr,
                userAgent = request?.getHeader("User-Agent"),
            ),
        )
    }

    @Transactional("tenantTransactionManager", readOnly = true)
    fun list(entity: String?, entityId: UUID?, pageable: Pageable): Page<AuditLogResponse> {
        val page = when {
            entity != null && entityId != null -> repo.findAllByEntityAndEntityId(entity, entityId, pageable)
            entity != null -> repo.findAllByEntity(entity, pageable)
            else -> repo.findAll(pageable)
        }
        return page.map { AuditLogResponse.from(it) }
    }

    private fun currentRequest(): HttpServletRequest? = try {
        (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
    } catch (_: Exception) {
        null
    }
}
