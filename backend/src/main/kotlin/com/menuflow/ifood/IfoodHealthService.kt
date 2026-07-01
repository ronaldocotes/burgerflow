package com.menuflow.ifood

import com.menuflow.model.control.IfoodIntegrationStatus
import com.menuflow.repository.control.IfoodTenantConfigRepository
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * Estado de saude da integracao iFood por tenant (banco de CONTROLE) + visao
 * agregada dos circuit breakers. Fase 5.1a: marcadores de estado + leitura; o
 * poller real (5.1b) e quem vai chamar markActive/markDegraded no ciclo.
 */
@Service
class IfoodHealthService(
    private val repo: IfoodTenantConfigRepository,
    private val circuitBreakerRegistry: CircuitBreakerRegistry,
) {
    /** Marca a integracao do tenant como DEGRADED e incrementa o contador de falhas. */
    fun markDegraded(companyId: UUID, reason: String) {
        repo.findByCompanyId(companyId)?.let {
            it.status = IfoodIntegrationStatus.DEGRADED
            it.consecutiveFailures++
            repo.save(it)
        }
    }

    /** Marca a integracao do tenant como ACTIVE, zera falhas e carimba o ultimo poll OK. */
    fun markActive(companyId: UUID) {
        repo.findByCompanyId(companyId)?.let {
            it.status = IfoodIntegrationStatus.ACTIVE
            it.consecutiveFailures = 0
            it.lastSuccessfulPoll = Instant.now()
            repo.save(it)
        }
    }

    /**
     * Percentual de circuit breakers ABERTOS (0..100). Sinal grosseiro de
     * indisponibilidade global do iFood. Sem breakers registrados -> 0.
     *
     * Nota: getAllCircuitBreakers() e um java.util.Set em R4j 2.2.0 -> isEmpty()
     * e size sao de kotlin.collections.Set (nao ha .isEmpty/.size() estilo vavr).
     */
    fun percentOpenCircuitBreakers(): Double {
        val all = circuitBreakerRegistry.allCircuitBreakers
        if (all.isEmpty()) return 0.0
        val open = all.count { it.state == CircuitBreaker.State.OPEN }
        return open.toDouble() / all.size * 100
    }

    /** Visao de saude por tenant (todos os merchants registrados no controle). */
    fun tenantHealth(): List<IfoodTenantHealthView> =
        repo.findAll().map {
            IfoodTenantHealthView(
                companyId = it.companyId,
                status = it.status,
                lastSuccessfulPoll = it.lastSuccessfulPoll,
                consecutiveFailures = it.consecutiveFailures,
            )
        }
}

data class IfoodTenantHealthView(
    val companyId: UUID,
    val status: IfoodIntegrationStatus,
    val lastSuccessfulPoll: Instant?,
    val consecutiveFailures: Int,
)
