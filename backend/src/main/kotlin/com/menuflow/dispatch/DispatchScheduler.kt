package com.menuflow.dispatch

import com.menuflow.repository.control.TenantRepository
import com.menuflow.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Motor do despacho por grupo (Fase B1). Em db-per-tenant o job de fundo nao tem
 * TenantContext, entao varremos o registro de tenants (banco de CONTROLE) e, para
 * cada tenant ATIVO, vinculamos o slug e (1) expiramos/reofertamos as ofertas de
 * grupo vencidas e (2) criamos as ofertas devidas dos pedidos elegiveis. O guard de
 * dispatch_enabled vive dentro do DispatchService (uma leitura de config por tenant).
 * Resiliente: falha num tenant nao impede os demais. Mesmo padrao do
 * DeliveryOfferExpiryJob / CartRecoveryJob.
 *
 * Desligavel por propriedade (menuflow.dispatch.enabled=false) -- usado nos testes
 * para o @Scheduled nao disparar contra o Postgres compartilhado da suite.
 */
@Component
@ConditionalOnProperty(
    name = ["menuflow.dispatch.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class DispatchScheduler(
    private val tenantRepository: TenantRepository,
    private val dispatchService: DispatchService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${menuflow.dispatch.interval-ms:30000}",
        initialDelayString = "\${menuflow.dispatch.interval-ms:30000}",
    )
    fun tick() {
        val tenants = tenantRepository.findAll().filter { it.isActive }
        var expired = 0
        var created = 0
        tenants.forEach { tenant ->
            try {
                TenantContext.set(tenant.slug)
                expired += dispatchService.expireAndReofferDue(tenant.slug)
                created += dispatchService.createDueOffers(tenant.slug)
            } catch (ex: Exception) {
                log.error("Falha no despacho do tenant {}: {}", tenant.slug, ex.message)
            } finally {
                TenantContext.clear()
            }
        }
        if (expired > 0 || created > 0) {
            log.info("Despacho: {} oferta(s) criada(s), {} expirada(s)", created, expired)
        }
    }
}
