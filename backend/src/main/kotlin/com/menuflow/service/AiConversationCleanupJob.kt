package com.menuflow.service

import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.tenant.AiConversationRepository
import com.menuflow.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Job noturno de retencao do historico do Copiloto (Fase 4 — completado). Em
 * db-per-tenant o job itera o registro de tenants (banco de CONTROLE) e, para
 * cada tenant ativo, deleta mensagens de ai_conversations mais antigas que
 * [RETENTION_DAYS] dias. Resiliente: erro num tenant nao impede os demais.
 *
 * Mesmo padrao do CartRecoveryJob. Desligavel por propriedade
 * (menuflow.ai-cleanup.enabled=false) — usado nos testes para o @Scheduled
 * nao disparar contra o Postgres compartilhado da suite.
 */
@Component
@ConditionalOnProperty(
    name = ["menuflow.ai-cleanup.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class AiConversationCleanupJob(
    private val tenantRepository: TenantRepository,
    private val aiConversationRepository: AiConversationRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Executa diariamente as 03:00 (horario do servidor; UTC em prod).
     * A janela de retencao de [RETENTION_DAYS]
     * dias e suficiente para analytics historico do painel e auditoria de conversas.
     */
    @Scheduled(cron = "0 0 3 * * *")
    fun run() {
        val cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS)
        val tenants = tenantRepository.findAll().filter { it.isActive }
        var totalDeleted = 0
        tenants.forEach { tenant ->
            try {
                TenantContext.set(tenant.slug)
                val deleted = aiConversationRepository.deleteByCreatedAtBefore(cutoff)
                if (deleted > 0) {
                    log.info("AI cleanup: {} mensagens removidas do tenant {}", deleted, tenant.slug)
                }
                totalDeleted += deleted
            } catch (ex: Exception) {
                log.error("AI cleanup: falha no tenant {}: {}", tenant.slug, ex.message)
            } finally {
                TenantContext.clear()
            }
        }
        if (totalDeleted > 0) log.info("AI cleanup total: {} mensagens removidas em {} tenants", totalDeleted, tenants.size)
    }

    companion object {
        /** Janela de retencao do historico do copiloto em dias. */
        const val RETENTION_DAYS = 90L
    }
}
