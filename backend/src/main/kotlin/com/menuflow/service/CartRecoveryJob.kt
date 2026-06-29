package com.menuflow.service

import com.menuflow.repository.control.TenantRepository
import com.menuflow.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Disparo periodico da recuperacao de carrinho abandonado (Fase 3.5). Em db-per-tenant
 * o job de fundo nao tem TenantContext, entao varremos o registro de tenants (banco de
 * CONTROLE) e, para cada tenant ATIVO, vinculamos o slug e processamos as comandas
 * pendentes no banco dele. Resiliente: erro num tenant nao impede os demais.
 *
 * Mesmo padrao do PixReconciliationJob. Desligavel por propriedade
 * (menuflow.cart-recovery.enabled=false) — usado nos testes para o @Scheduled nao
 * disparar contra o Postgres compartilhado da suite.
 */
@Component
@ConditionalOnProperty(
    name = ["menuflow.cart-recovery.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class CartRecoveryJob(
    private val tenantRepository: TenantRepository,
    private val cartRecoveryService: CartRecoveryService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // initialDelay para nao rodar na subida do contexto; a cada intervalo depois
    // (default 30 min). O atraso por pedido e checado por comanda no service.
    @Scheduled(
        fixedDelayString = "\${menuflow.cart-recovery.interval-ms:1800000}",
        initialDelayString = "\${menuflow.cart-recovery.interval-ms:1800000}",
    )
    fun run() {
        val tenants = tenantRepository.findAll().filter { it.isActive }
        var totalSent = 0
        tenants.forEach { tenant ->
            try {
                TenantContext.set(tenant.slug)
                totalSent += cartRecoveryService.processAbandonedCarts(tenant.slug)
            } catch (ex: Exception) {
                log.error("Falha na recuperacao de carrinho do tenant {}: {}", tenant.slug, ex.message)
            } finally {
                TenantContext.clear()
            }
        }
        if (totalSent > 0) log.info("Recuperacao de carrinho: {} mensagens enviadas", totalSent)
    }
}
