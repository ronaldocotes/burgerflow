package com.burgerflow.repository.control

import com.burgerflow.model.control.Tenant
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface TenantRepository : JpaRepository<Tenant, UUID> {
    fun findBySlug(slug: String): Tenant?
    fun existsBySlug(slug: String): Boolean
}
