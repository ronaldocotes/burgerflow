package com.burgerflow.repository

import com.burgerflow.model.Tenant
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface TenantRepository : JpaRepository<Tenant, UUID> {
    
    fun findByName(name: String): Tenant?
    
    fun findBySchemaName(schemaName: String): Tenant?
    
    fun existsByName(name: String): Boolean
    
    fun existsBySchemaName(schemaName: String): Boolean
    
    fun findByIsActive(isActive: Boolean): List<Tenant>
    
    @Query("SELECT t FROM Tenant t WHERE t.isActive = true AND t.expiresAt IS NULL OR t.expiresAt > CURRENT_TIMESTAMP")
    fun findAllActive(): List<Tenant>
    
    @Query("SELECT t FROM Tenant t WHERE t.expiresAt <= CURRENT_TIMESTAMP AND t.isActive = true")
    fun findExpiredTenants(): List<Tenant>
}
