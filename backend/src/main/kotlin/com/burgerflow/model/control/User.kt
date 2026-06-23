package com.burgerflow.model.control

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Auth principal, stored in the CONTROL database. A user belongs to exactly one
 * tenant (by [tenantId]); login is scoped by (tenantId, email) so the same email
 * may exist across different hamburguerias.
 */
@Entity
@Table(
    name = "users",
    uniqueConstraints = [UniqueConstraint(columnNames = ["tenant_id", "email"])],
)
data class User(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "tenant_id", nullable = false)
    var tenantId: UUID,

    @Column(nullable = false)
    var email: String,

    @Column(name = "password_hash", nullable = false)
    var passwordHash: String,

    @Column(name = "first_name", nullable = false)
    var firstName: String,

    @Column(name = "last_name", nullable = false)
    var lastName: String = "",

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var role: UserRole = UserRole.STAFF,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "last_login_at")
    var lastLoginAt: Instant? = null,
) {
    @PreUpdate
    fun preUpdate() {
        updatedAt = Instant.now()
    }

    val fullName: String
        get() = "$firstName $lastName".trim()
}

enum class UserRole {
    ADMIN,
    MANAGER,
    STAFF,
    CASHIER,
    KITCHEN,
    DELIVERY,
}
