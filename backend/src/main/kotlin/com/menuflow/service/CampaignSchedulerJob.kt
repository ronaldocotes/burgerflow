package com.menuflow.service

import com.menuflow.repository.control.TenantRepository
import com.menuflow.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Disparo periodico das campanhas AGENDADAS (fix da Fase 3.4). Campanhas criadas
 * com scheduledAt nascem SCHEDULED; este job varre cada tenant ATIVO a cada tick
 * (default 60s) e promove SCHEDULED -> RUNNING de forma ATOMICA (UPDATE com guard
 * no status — ver CampaignService.startDueScheduled), delegando o envio ao MESMO
 * caminho do start manual (CampaignDispatcher.dispatchAsync, com delays anti-ban
 * e recheque de opt-in por envio). PAUSED nunca e disparado pelo agendador.
 *
 * Em db-per-tenant o job de fundo nao tem TenantContext: varremos o registro de
 * tenants (banco de CONTROLE) e vinculamos o slug antes de processar — mesmo
 * padrao do CartRecoveryJob/ConversionDispatchJob. Resiliente: erro num tenant
 * nao impede os demais. Desligavel por propriedade
 * (menuflow.campaign-scheduler.enabled=false) — usado nos testes para o
 * @Scheduled nao disparar contra o Postgres compartilhado da suite.
 */
@Component
@ConditionalOnProperty(
    name = ["menuflow.campaign-scheduler.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class CampaignSchedulerJob(
    private val tenantRepository: TenantRepository,
    private val campaignService: CampaignService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // initialDelay para nao rodar na subida do contexto; depois a cada tick
    // (default 60s — precisao de minuto e suficiente para campanha de marketing).
    @Scheduled(
        fixedDelayString = "\${menuflow.campaign-scheduler.interval-ms:60000}",
        initialDelayString = "\${menuflow.campaign-scheduler.interval-ms:60000}",
    )
    fun run() {
        val tenants = tenantRepository.findAll().filter { it.isActive }
        var totalStarted = 0
        tenants.forEach { tenant ->
            try {
                TenantContext.set(tenant.slug)
                totalStarted += campaignService.startDueScheduled(tenant.slug)
            } catch (ex: Exception) {
                log.error("Falha no agendador de campanhas do tenant {}: {}", tenant.slug, ex.message)
            } finally {
                TenantContext.clear()
            }
        }
        if (totalStarted > 0) log.info("Campanhas agendadas disparadas neste tick: {}", totalStarted)
    }
}
