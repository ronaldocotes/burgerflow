package com.menuflow.repository.control

import com.menuflow.model.control.User
import com.menuflow.model.control.UserRole
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface UserRepository : JpaRepository<User, UUID> {
    /** Login is scoped by (tenant, email): the same email may exist in two tenants. */
    fun findByTenantIdAndEmail(tenantId: UUID, email: String): User?
    fun existsByTenantIdAndEmail(tenantId: UUID, email: String): Boolean

    /** Listagem de usuários do tenant (módulo de gestão de usuários). */
    fun findAllByTenantId(tenantId: UUID): List<User>

    /** Contagem de admins ATIVOS do tenant — base da proteção anti-lockout. */
    fun countByTenantIdAndRoleAndIsActiveTrue(tenantId: UUID, role: UserRole): Long

    /** Todos os usuários com este e-mail em QUALQUER tenant — usado no bootstrap
     *  do primeiro SUPER_ADMIN (o e-mail sozinho não é único entre tenants). */
    fun findAllByEmail(email: String): List<User>
}
