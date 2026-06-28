package com.menuflow.model

import jakarta.persistence.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Acerto financeiro de um entregador (DeliveryDriver) por periodo — vive no banco
 * do TENANT. Ao fechar (status CLOSED) congela o periodo: deliveries_count,
 * working_days e os totais ficam imutaveis. Dinheiro SEMPRE em centavos.
 *
 * [driverId] referencia delivery_drivers.id (mesmo id de orders.driver_id), usado
 * para contar as entregas DELIVERED do periodo no fechamento.
 *
 * Invariante: no maximo 1 acerto OPEN por entregador (indice parcial
 * uq_settlement_open + checagem existsByDriverIdAndStatus no servico).
 */
@Entity
@Table(name = "driver_settlements")
data class DriverSettlement(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "driver_id", nullable = false)
    val driverId: UUID,

    @Column(name = "period_start", nullable = false)
    val periodStart: LocalDate,

    @Column(name = "period_end", nullable = false)
    val periodEnd: LocalDate,

    @Column(name = "deliveries_count", nullable = false)
    var deliveriesCount: Int = 0,

    @Column(name = "working_days", nullable = false)
    var workingDays: Int = 0,

    @Column(name = "daily_total_cents", nullable = false)
    var dailyTotalCents: Long = 0,

    @Column(name = "delivery_total_cents", nullable = false)
    var deliveryTotalCents: Long = 0,

    @Column(name = "km_total_cents", nullable = false)
    var kmTotalCents: Long = 0,

    // Coluna gerada pelo banco (daily+delivery+km). Read-only para o ORM. O
    // servico calcula o bruto em memoria para a resposta (evita reler a coluna
    // gerada, que ficaria stale na mesma transacao apos o save).
    @Column(name = "gross_total_cents", insertable = false, updatable = false)
    val grossTotalCents: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: DriverSettlementStatus = DriverSettlementStatus.OPEN,

    @Column(name = "closed_by_user_id")
    var closedByUserId: UUID? = null,

    @Column(name = "closed_at")
    var closedAt: Instant? = null,

    @Column(name = "notes")
    var notes: String? = null,

    @Version
    @Column(nullable = false)
    var version: Long = 0,

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

enum class DriverSettlementStatus {
    OPEN,
    CLOSED,
}
