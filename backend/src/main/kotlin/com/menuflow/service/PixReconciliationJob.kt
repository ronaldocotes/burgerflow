package com.menuflow.service

import com.menuflow.repository.control.TenantRepository
import com.menuflow.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Reconciliacao periodica das cobrancas PIX. Em db-per-tenant nao ha TenantContext
 * num job de fundo, entao varremos o registro de tenants (banco de CONTROLE) e, para
 * cada tenant ativo, vinculamos o slug e expiramos as cobrancas vencidas no banco
 * dele. Resiliente: erro num tenant nao impede os demais.
 *
 * Desligavel por propriedade (menuflow.asaas.reconcile.enabled=false) — usado nos
 * testes para o @Scheduled nao disparar contra o Postgres compartilhado.
 */
@Component
@ConditionalOnProperty(
    name = ["menuflow.asaas.reconcile.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class PixReconciliationJob(
    private val tenantRepository: TenantRepository,
    private val pixPaymentService: PixPaymentService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // initialDelay para nao rodar na subida do contexto; a cada 5 min depois.
    @Scheduled(fixedDelayString = "300000", initialDelayString = "300000")
    fun reconcile() {
        val tenants = tenantRepository.findAll().filter { it.isActive }
        var totalExpired = 0
        tenants.forEach { tenant ->
            try {
                TenantContext.set(tenant.slug)
                totalExpired += pixPaymentService.expireOverdueForCurrentTenant()
            } catch (ex: Exception) {
                log.error("Falha ao reconciliar PIX do tenant {}: {}", tenant.slug, ex.message)
            } finally {
                TenantContext.clear()
            }
        }
        if (totalExpired > 0) log.info("Reconciliacao PIX: {} cobrancas expiradas", totalExpired)
    }
}
