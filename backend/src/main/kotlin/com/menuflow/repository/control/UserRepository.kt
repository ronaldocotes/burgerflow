package com.menuflow.repository.control

import com.menuflow.model.control.User
import com.menuflow.model.control.UserRole
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
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

    /** Último login de qualquer usuário do tenant — métrica para o snapshot de uso. */
    @Query("SELECT MAX(u.lastLoginAt) FROM User u WHERE u.tenantId = :tenantId")
    fun findMaxLastLoginAtByTenantId(@Param("tenantId") tenantId: UUID): Instant?

    /** Todos os SUPER_ADMINs do sistema (cross-tenant) — painel de gestão de plataforma. */
    fun findAllByRole(role: UserRole): List<User>

    /** Contagem de SUPER_ADMINs ativos no sistema — base do anti-lockout de plataforma. */
    fun countByRoleAndIsActiveTrue(role: UserRole): Long
}
