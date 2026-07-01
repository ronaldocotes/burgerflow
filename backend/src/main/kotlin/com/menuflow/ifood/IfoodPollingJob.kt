package com.menuflow.ifood

import com.menuflow.model.control.IfoodIntegrationStatus
import com.menuflow.repository.control.IfoodTenantConfigRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Job de polling do iFood (Fase 5.1a — STUB). DESABILITADO por padrao
 * (ifood.polling.enabled=false): so vira bean quando explicitamente ligado, e
 * ainda assim nao faz chamada HTTP real ao iFood.
 *
 * Nesta fase ele apenas conta os tenants ACTIVE. O polling real (buscar eventos,
 * fazer ack, materializar pedidos) entra na Fase 5.1b, depois de confirmar a
 * topologia do token na homologacao do iFood.
 */
@Component
@ConditionalOnProperty("ifood.polling.enabled", havingValue = "true", matchIfMissing = false)
class IfoodPollingJob(
    private val ifoodTenantConfigRepository: IfoodTenantConfigRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${ifood.polling.interval-ms:30000}")
    fun poll() {
        val active = ifoodTenantConfigRepository.findByStatus(IfoodIntegrationStatus.ACTIVE)
        if (active.isEmpty()) return
        log.info("[iFood poller] tenants ativos: {} — stub, sem polling real ainda", active.size)
        // 5.1b: implementar polling real apos confirmar topologia do token na homologacao.
    }
}
