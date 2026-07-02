package com.menuflow.model.control

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Uso de IA agregado por tenant e mes (Fase 4.1). Vive no banco de CONTROLE para o
 * faturamento ser consolidavel por empresa (o copiloto e cobrado por consumo).
 *
 * Uma linha por (tenant_id, month_year): o acumulado do mes faz upsert sobre essa
 * chave. tenant_id e um UUID SEM foreign key (ver V5__ai_usage.sql) -- registro de
 * faturamento nao some quando o tenant e removido. O controle roda ddl-auto=validate,
 * entao os @Column abaixo precisam bater exatamente com as migracoes V5 e V14.
 */
@Entity
@Table(name = "ai_usage")
data class AiUsage(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "tenant_id", nullable = false)
    var tenantId: UUID,

    @Column(name = "tenant_slug", nullable = false)
    var tenantSlug: String,

    /** Mes de referencia no formato "AAAA-MM" (America/Sao_Paulo). */
    @Column(name = "month_year", nullable = false, length = 7)
    var monthYear: String,

    @Column(name = "prompt_tokens", nullable = false)
    var promptTokens: Long = 0,

    @Column(name = "completion_tokens", nullable = false)
    var completionTokens: Long = 0,

    @Column(name = "total_requests", nullable = false)
    var totalRequests: Long = 0,

    /**
     * Custo estimado acumulado no mes em micros de USD (1 USD = 1_000_000 micros).
     * Snapshot calculado por [com.menuflow.service.AiPricingTable] na gravacao --
     * nao recalcular retroativamente. Linhas anteriores a V14 ficam com 0 (correto:
     * custo desconhecido, nao custo zero).
     */
    @Column(name = "estimated_cost_usd_micros", nullable = false)
    var estimatedCostUsdMicros: Long = 0L,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun preUpdate() {
        updatedAt = Instant.now()
    }
}
