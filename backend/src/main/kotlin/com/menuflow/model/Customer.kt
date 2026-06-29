package com.menuflow.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "customers")
data class Customer(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(nullable = false)
    var name: String,

    @Column(name = "phone_number", nullable = false, unique = true)
    var phoneNumber: String,

    @Column(unique = true)
    var email: String? = null,

    @Column(name = "address_line_1")
    var addressLine1: String? = null,

    @Column(name = "neighborhood")
    var neighborhood: String? = null,

    @Column(name = "city")
    var city: String? = null,

    @Column(name = "zip_code")
    var zipCode: String? = null,

    /** Default delivery fee for this customer, in centavos. */
    @Column(name = "delivery_fee_cents")
    var deliveryFeeCents: Long? = null,

    @Column(name = "loyalty_points", nullable = false)
    var loyaltyPoints: Int = 0,

    // --- Opt-in de marketing (Fase 3.4) ---
    /** Consentimento explicito para receber campanhas (pre-requisito anti-ban WAHA). */
    @Column(name = "marketing_opt_in", nullable = false)
    var marketingOptIn: Boolean = false,

    /** Quando concedeu o opt-in. */
    @Column(name = "opt_in_at")
    var optInAt: Instant? = null,

    /** Quando revogou o opt-in (descadastro). */
    @Column(name = "opt_out_at")
    var optOutAt: Instant? = null,

    @Column(name = "active", nullable = false)
    var active: Boolean = true,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun preUpdate() {
        updatedAt = Instant.now()
    }
}
