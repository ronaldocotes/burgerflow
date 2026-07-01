package com.menuflow.opendelivery

import com.menuflow.model.control.OpenDeliveryIntegrationStatus
import com.menuflow.repository.control.OpenDeliveryTenantConfigRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Job de polling Open Delivery (99Food / Rappi) — Fase 5.5a, STUB. DESABILITADO
 * por padrao (open-delivery.polling.enabled=false): so vira bean quando ligado, e
 * ainda assim nao faz chamada HTTP real.
 *
 * Espelha o IfoodPollingJob: por ora apenas conta os tenants ACTIVE. O polling real
 * (GET .../events:polling -> persistir em open_delivery_event_log -> ACK) entra na
 * Fase 5.5b, junto do cliente HTTP e do OAuth2 client_credentials.
 */
@Component
@ConditionalOnProperty("open-delivery.polling.enabled", havingValue = "true", matchIfMissing = false)
class OpenDeliveryPollingJob(
    private val repo: OpenDeliveryTenantConfigRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${open-delivery.polling.interval-ms:30000}")
    fun poll() {
        val active = repo.findByStatus(OpenDeliveryIntegrationStatus.ACTIVE)
        if (active.isEmpty()) return
        log.info("[OD poller] tenants ativos: {} — stub, polling real na 5.5b", active.size)
        // 5.5b: GET {baseUrl}/open-delivery-api/v1/events:polling -> persist -> ACK
    }
}
