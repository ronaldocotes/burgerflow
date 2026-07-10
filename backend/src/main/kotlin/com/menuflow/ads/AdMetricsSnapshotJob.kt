package com.menuflow.ads

import com.menuflow.repository.control.TenantRepository
import com.menuflow.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Coleta horaria das metricas de anuncio da Meta (Fase 8.1), a nivel de CONTA. Em
 * db-per-tenant o job de fundo nao tem TenantContext: varremos o registro de tenants
 * (banco de CONTROLE) e vinculamos o slug antes de coletar — mesmo padrao do
 * CampaignSchedulerJob/ConversionDispatchJob. Resiliente: erro num tenant nao impede os
 * demais (e o AdMetricsService ja isola por conta la dentro).
 *
 * 1 chamada por conta/hora e volume baixo; ainda assim o service trata rate-limit da Meta
 * pulando so a conta afetada no tick. Desligavel por propriedade
 * (menuflow.ads.metrics.enabled=false) — usado nos testes para o @Scheduled nao disparar
 * contra o Postgres compartilhado da suite nem fazer HTTP real para a Meta.
 */
@Component
@ConditionalOnProperty(
    name = ["menuflow.ads.metrics.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class AdMetricsSnapshotJob(
    private val tenantRepository: TenantRepository,
    private val adMetricsService: AdMetricsService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // initialDelay para nao rodar na subida do contexto; depois a cada intervalo
    // (default 1h). Metrica de anuncio consolida ao longo do dia; hora e granularidade ok.
    @Scheduled(
        fixedDelayString = "\${menuflow.ads.metrics.interval-ms:3600000}",
        initialDelayString = "\${menuflow.ads.metrics.interval-ms:3600000}",
    )
    fun run() {
        val tenants = tenantRepository.findAll().filter { it.isActive }
        tenants.forEach { tenant ->
            try {
                TenantContext.set(tenant.slug)
                adMetricsService.snapshotAllAccounts()
            } catch (ex: Exception) {
                log.error("Falha na coleta de metricas de anuncio do tenant {}: {}", tenant.slug, ex.message)
            } finally {
                TenantContext.clear()
            }
        }
    }
}
