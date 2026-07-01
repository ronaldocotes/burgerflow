package com.menuflow.platform

import com.fasterxml.jackson.databind.ObjectMapper
import com.menuflow.repository.control.UserRepository
import com.menuflow.security.SecurityUtils
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.util.UUID

/**
 * Grava a trilha de plataforma (platform_audit_log, banco de CONTROLE). Toda mutação
 * do painel super-admin passa por aqui.
 *
 * Propagação REQUIRES_NEW: a auditoria abre uma transação PRÓPRIA (no
 * controlTransactionManager) e comita independente da transação de negócio. Assim,
 * se o fluxo principal falhar/rolar depois, o registro de tentativa permanece — e,
 * inversamente, uma falha ao auditar nunca deve derrubar a operação (por isso o
 * record() engole exceção e só loga). Append-only: só INSERT.
 *
 * PII/segredos: quem chama já manda before/after MASCARADOS (só ids, slugs, flags,
 * papéis). Este serviço não tenta redigir — confia no contrato do chamador.
 */
@Service
class PlatformAuditService(
    private val auditRepo: PlatformAuditLogRepository,
    private val userRepository: UserRepository,
    private val objectMapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional("controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    fun record(
        action: String,
        targetTenantId: UUID? = null,
        targetEntity: String? = null,
        before: Any? = null,
        after: Any? = null,
    ) {
        try {
            val principal = SecurityUtils.currentPrincipal() ?: return
            // E-mail do ator vem do banco de controle (o principal não o carrega).
            val actorEmail = userRepository.findById(principal.userId).orElse(null)?.email ?: "?"
            auditRepo.save(
                PlatformAuditLog(
                    actorUserId = principal.userId,
                    actorEmail = actorEmail,
                    action = action,
                    targetTenantId = targetTenantId,
                    targetEntity = targetEntity,
                    beforeJson = before?.let { objectMapper.writeValueAsString(it) },
                    afterJson = after?.let { objectMapper.writeValueAsString(it) },
                    sourceIp = currentIp(),
                ),
            )
        } catch (ex: Exception) {
            // Auditoria nunca derruba o fluxo — mas a falha é anômala, logar alto.
            log.error("[PLATFORM AUDIT] Falha ao gravar trilha (action={})", action, ex)
        }
    }

    private fun currentIp(): String? =
        (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)
            ?.request
            ?.let { it.getHeader("X-Forwarded-For")?.substringBefore(",")?.trim() ?: it.remoteAddr }
            ?.take(45)
}
