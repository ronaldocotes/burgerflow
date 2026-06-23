package com.burgerflow.model

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "users")
data class User(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,
    
    @Column(nullable = false)
    var tenantId: UUID,
    
    @Column(nullable = false, unique = true)
    var email: String,
    
    @Column(nullable = false)
    var passwordHash: String,
    
    @Column(nullable = false)
    var firstName: String,
    
    @Column(nullable = false)
    var lastName: String,
    
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var role: UserRole = UserRole.STAFF,
    
    @Column(nullable = false)
    var isActive: Boolean = true,
    
    @Column(name = "email_verified", nullable = false)
    var isEmailVerified: Boolean = false,
    
    @Column(name = "phone_number")
    var phoneNumber: String? = null,
    
    @Column(name = "profile_image_url")
    var profileImageUrl: String? = null,
    
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    
    @Column(name = "updated_at")
    var updatedAt: LocalDateTime = LocalDateTime.now(),
    
    @Column(name = "last_login_at")
    var lastLoginAt: LocalDateTime? = null
) {
    @PreUpdate
    fun preUpdate() {
        updatedAt = LocalDateTime.now()
    }
    
    val fullName: String
        get() = "$firstName $lastName"
}

enum class UserRole {
    ADMIN,
    MANAGER,
    STAFF,
    CASHIER,
    KITCHEN,
    DELIVERY
}
