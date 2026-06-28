package com.menuflow.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Configuracao de remuneracao de um entregador (DeliveryDriver) — vive no banco
 * do TENANT (db-per-tenant: escopo garantido pelo datasource roteado). Dinheiro
 * SEMPRE em centavos.
 *
 * [driverId] referencia delivery_drivers.id (o mesmo id gravado em orders.driver_id),
 * NAO um usuario do banco de controle: o acerto paga o entregador cujas entregas
 * sao contadas por orders.driver_id. UNIQUE: no maximo uma config por entregador.
 */
@Entity
@Table(name = "driver_configs")
data class DriverConfig(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "driver_id", nullable = false, unique = true)
    val driverId: UUID,

    @Column(name = "daily_rate_cents", nullable = false)
    var dailyRateCents: Long = 0,

    @Column(name = "per_delivery_cents", nullable = false)
    var perDeliveryCents: Long = 0,

    @Column(name = "per_km_cents", nullable = false)
    var perKmCents: Long = 0,

    @Column(name = "notes")
    var notes: String? = null,

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
