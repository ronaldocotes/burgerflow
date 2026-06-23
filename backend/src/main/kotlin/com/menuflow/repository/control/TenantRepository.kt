package com.menuflow.repository.control

import com.menuflow.model.control.Tenant
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface TenantRepository : JpaRepository<Tenant, UUID> {
    fun findBySlug(slug: String): Tenant?
    fun existsBySlug(slug: String): Boolean
}
