package com.burgerflow.model.control

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Registry row in the CONTROL database. Each tenant maps to a physical Postgres
 * database named `tenant_<slug>`. The control DB never holds business data.
 */
@Entity
@Table(name = "tenants")
data class Tenant(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    /** Logical tenant key used in the X-Tenant-ID header / JWT, e.g. "abc". */
    @Column(nullable = false, unique = true)
    var slug: String,

    @Column(nullable = false)
    var displayName: String,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var subscriptionPlan: SubscriptionPlan = SubscriptionPlan.BASIC,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "expires_at")
    var expiresAt: Instant? = null,
) {
    @PreUpdate
    fun preUpdate() {
        updatedAt = Instant.now()
    }
}

enum class SubscriptionPlan {
    BASIC,
    PRO,
    ENTERPRISE,
}
