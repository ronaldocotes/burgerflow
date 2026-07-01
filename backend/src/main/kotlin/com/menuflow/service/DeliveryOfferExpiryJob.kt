package com.menuflow.service

import com.menuflow.repository.control.TenantRepository
import com.menuflow.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Expira as ofertas de entrega OFFERED vencidas (Fase 6.1). Em db-per-tenant o job de
 * fundo nao tem TenantContext, entao varremos o registro de tenants (banco de CONTROLE)
 * e, para cada tenant ATIVO, vinculamos o slug e expiramos as ofertas vencidas no banco
 * dele. Resiliente: erro num tenant nao impede os demais. Mesmo padrao do CartRecoveryJob.
 *
 * Desligavel por propriedade (menuflow.delivery.expiry.enabled=false) — usado nos testes
 * para o @Scheduled nao disparar contra o Postgres compartilhado da suite.
 */
@Component
@ConditionalOnProperty(
    name = ["menuflow.delivery.expiry.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class DeliveryOfferExpiryJob(
    private val tenantRepository: TenantRepository,
    private val autoAssignService: AutoAssignService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // A cada 10s por padrao: a janela de aceite (offer_timeout_seconds) e curta.
    @Scheduled(
        fixedDelayString = "\${menuflow.delivery.expiry.interval-ms:10000}",
        initialDelayString = "\${menuflow.delivery.expiry.interval-ms:10000}",
    )
    fun run() {
        val tenants = tenantRepository.findAll().filter { it.isActive }
        var totalExpired = 0
        tenants.forEach { tenant ->
            try {
                TenantContext.set(tenant.slug)
                totalExpired += autoAssignService.expireStaleOffers()
            } catch (ex: Exception) {
                log.error("Falha ao expirar ofertas do tenant {}: {}", tenant.slug, ex.message)
            } finally {
                TenantContext.clear()
            }
        }
        if (totalExpired > 0) log.info("Ofertas de entrega expiradas: {}", totalExpired)
    }
}
