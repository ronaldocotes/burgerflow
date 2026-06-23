package com.burgerflow.model

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "customers")
data class Customer(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,
    
    @Column(nullable = false)
    var tenantId: UUID,
    
    @Column(nullable = false)
    var name: String,
    
    @Column(name = "phone_number", nullable = false, unique = true)
    var phoneNumber: String,
    
    @Column(unique = true)
    var email: String? = null,
    
    @Column(name = "address_line_1")
    var addressLine1: String? = null,
    
    @Column(name = "address_line_2")
    var addressLine2: String? = null,
    
    @Column(name = "neighborhood")
    var neighborhood: String? = null,
    
    @Column(name = "city")
    var city: String? = null,
    
    @Column(name = "state")
    var state: String? = null,
    
    @Column(name = "zip_code")
    var zipCode: String? = null,
    
    @Column(name = "distance_km", precision = 10, scale = 2)
    var distanceKm: Double? = null,
    
    @Column(name = "delivery_fee", precision = 10, scale = 2)
    var deliveryFee: Double? = null,
    
    @Column(name = "is_loyalty_member", nullable = false)
    var isLoyaltyMember: Boolean = false,
    
    @Column(name = "loyalty_points", nullable = false)
    var loyaltyPoints: Int = 0,
    
    @Column(name = "notes")
    var notes: String? = null,
    
    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,
    
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    
    @Column(name = "updated_at")
    var updatedAt: LocalDateTime = LocalDateTime.now(),
    
    @Column(name = "last_order_at")
    var lastOrderAt: LocalDateTime? = null,
    
    @Column(name = "total_spent", precision = 12, scale = 2)
    var totalSpent: Double = 0.0
) {
    @PreUpdate
    fun preUpdate() {
        updatedAt = LocalDateTime.now()
    }
    
    val formattedPhone: String
        get() = phoneNumber.replace("[^0-9]".toRegex(), "")
    
    val fullAddress: String
        get() = listOfNotNull(
            addressLine1,
            addressLine2,
            neighborhood,
            city,
            state,
            zipCode
        ).joinToString(", ")
}
