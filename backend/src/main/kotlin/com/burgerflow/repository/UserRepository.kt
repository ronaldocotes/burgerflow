package com.burgerflow.repository

import com.burgerflow.model.User
import com.burgerflow.model.UserRole
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface UserRepository : JpaRepository<User, UUID> {
    
    fun findByEmail(email: String): User?
    
    fun findByTenantIdAndEmail(tenantId: UUID, email: String): User?
    
    fun existsByEmail(email: String): Boolean
    
    fun existsByTenantIdAndEmail(tenantId: UUID, email: String): Boolean
    
    fun findByTenantId(tenantId: UUID): List<User>
    
    fun findByTenantIdAndIsActive(tenantId: UUID, isActive: Boolean): List<User>
    
    fun findByTenantIdAndRole(tenantId: UUID, role: UserRole): List<User>
    
    @Query("SELECT u FROM User u WHERE u.tenantId = :tenantId AND u.isActive = true")
    fun findAllActiveByTenant(tenantId: UUID): List<User>
    
    @Query("SELECT COUNT(u) > 0 FROM User u WHERE u.tenantId = :tenantId AND u.role = 'ADMIN'")
    fun existsAdminByTenant(tenantId: UUID): Boolean
    
    fun deleteByTenantId(tenantId: UUID): Int
}
