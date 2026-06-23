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
