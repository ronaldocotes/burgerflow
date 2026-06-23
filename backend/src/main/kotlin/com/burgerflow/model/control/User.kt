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

    // Sprint 2 roles. OPERATOR = PDV/cashier operator (front of house);
    // DRIVER = delivery courier. Added additively; stored as STRING so existing
    // rows are unaffected. RBAC: PDV requires OPERATOR or ADMIN; Delivery accepts
    // DRIVER, OPERATOR or ADMIN (see PdvController / DeliveryController).
    OPERATOR,
    DRIVER,

    // PLATFORM role (cross-tenant), NOT a tenant role. Grants access to platform
    // operations that read across ALL tenants (e.g. the migration drift-check at
    // GET /admin/tenants/migration-status). It must be assigned deliberately and
    // sparingly; an ordinary tenant ADMIN must never receive it, otherwise it
    // would expose other hamburguerias' state (cross-tenant IDOR). The role name
    // flows into the JWT as ROLE_SUPER_ADMIN via the normal AuthService path.
    SUPER_ADMIN,
}
