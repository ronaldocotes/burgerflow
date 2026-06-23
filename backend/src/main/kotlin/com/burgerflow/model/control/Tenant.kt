package com.burgerflow.model

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "tenants")
data class Tenant(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,
    
    @Column(nullable = false, unique = true)
    var name: String,
    
    @Column(nullable = false, unique = true)
    var schemaName: String,
    
    @Column(nullable = false)
    var displayName: String,
    
    @Column(nullable = false)
    var subscriptionPlan: SubscriptionPlan = SubscriptionPlan.BASIC,
    
    @Column(nullable = false)
    var maxUsers: Int = 10,
    
    @Column(nullable = false)
    var maxProducts: Int = 100,
    
    @Column(nullable = false)
    var isActive: Boolean = true,
    
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    
    @Column(name = "updated_at")
    var updatedAt: LocalDateTime = LocalDateTime.now(),
    
    @Column(name = "expires_at")
    var expiresAt: LocalDateTime? = null
) {
    @PreUpdate
    fun preUpdate() {
        updatedAt = LocalDateTime.now()
    }
}

enum class SubscriptionPlan {
    BASIC,
    PRO,
    ENTERPRISE
}
