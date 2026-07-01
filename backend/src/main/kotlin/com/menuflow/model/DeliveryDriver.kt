package com.menuflow.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * A delivery courier (Sprint 2). Lives in the TENANT database, so it is already
 * scoped to the hamburgueria by construction (one DB per tenant). [tenantId] is
 * stored redundantly for traceability/auditing only; it is NOT relied upon for
 * isolation (the physical database boundary provides that).
 */
@Entity
@Table(name = "delivery_drivers")
data class DeliveryDriver(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "name", nullable = false)
    var name: String,

    @Column(name = "phone", nullable = false)
    var phone: String,

    @Column(name = "license_plate")
    var licensePlate: String? = null,

    @Column(name = "active", nullable = false)
    var active: Boolean = true,

    /** Tenant UUID (from the signed JWT) recorded for audit; not an isolation key. */
    @Column(name = "tenant_id", nullable = false)
    var tenantId: UUID,

    // --- Fase 6.1: elo de autenticacao, turno e ultima localizacao ---
    /** User (banco de controle, papel DRIVER) ligado a este entregador. Elo 1:1. */
    @Column(name = "user_id")
    var userId: UUID? = null,

    /** Turno ativo: motoboy online e disponivel para receber ofertas de entrega. */
    @Column(name = "active_shift", nullable = false)
    var activeShift: Boolean = false,

    /** Ultima latitude reportada pelo app (mapa ao vivo e auto-assign). */
    @Column(name = "last_lat")
    var lastLat: Double? = null,

    /** Ultima longitude reportada pelo app. */
    @Column(name = "last_lng")
    var lastLng: Double? = null,

    /** Momento da ultima posicao reportada. */
    @Column(name = "last_location_at")
    var lastLocationAt: Instant? = null,

    /** Nivel de bateria (%) reportado pelo app; telemetria. */
    @Column(name = "battery_pct")
    var batteryPct: Int? = null,

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
