package com.menuflow.service

import com.menuflow.repository.control.TenantRepository
import com.menuflow.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Disparo periodico do rastreamento de conversao (Fase 3.7). Em db-per-tenant o job
 * de fundo nao tem TenantContext, entao varremos o registro de tenants (banco de
 * CONTROLE) e, para cada tenant ATIVO, processamos os despachos pendentes/falhos no
 * banco dele. Resiliente: erro num tenant nao impede os demais.
 *
 * Mesmo padrao do CartRecoveryJob/PixReconciliationJob. Desligavel por propriedade
 * (menuflow.conversions.enabled=false) — usado nos testes para o @Scheduled nao
 * disparar contra o Postgres compartilhado da suite.
 *
 * Este unico job cobre o envio inicial (PENDING) E o retry com backoff (FAILED ate
 * MAX_ATTEMPTS, depois SKIPPED), conforme [ConversionService.processDispatches].
 */
@Component
@ConditionalOnProperty(
    name = ["menuflow.conversions.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class ConversionDispatchJob(
    private val tenantRepository: TenantRepository,
    private val conversionService: ConversionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // initialDelay para nao rodar na subida do contexto; depois a cada intervalo
    // (default 30 min). A conversao offline tolera atraso de minutos sem problema.
    @Scheduled(
        fixedDelayString = "\${menuflow.conversions.interval-ms:1800000}",
        initialDelayString = "\${menuflow.conversions.interval-ms:1800000}",
    )
    fun run() {
        val tenants = tenantRepository.findAll().filter { it.isActive }
        var totalSent = 0
        tenants.forEach { tenant ->
            try {
                totalSent += conversionService.processDispatches(tenant.slug)
            } catch (ex: Exception) {
                log.error("Falha no despacho de conversoes do tenant {}: {}", tenant.slug, ex.message)
            } finally {
                TenantContext.clear()
            }
        }
        if (totalSent > 0) log.info("Conversoes despachadas: {} eventos enviados", totalSent)
    }
}
