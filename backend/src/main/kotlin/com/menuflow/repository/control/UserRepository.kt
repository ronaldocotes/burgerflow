package com.menuflow.repository.control

import com.menuflow.model.control.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface UserRepository : JpaRepository<User, UUID> {
    /** Login is scoped by (tenant, email): the same email may exist in two tenants. */
    fun findByTenantIdAndEmail(tenantId: UUID, email: String): User?
    fun existsByTenantIdAndEmail(tenantId: UUID, email: String): Boolean
}
