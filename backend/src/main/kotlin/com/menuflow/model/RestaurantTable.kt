package com.menuflow.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Mesa física do salão. Vive no banco do TENANT. O rótulo é único somente entre
 * mesas ativas (índice parcial em V11) — desativar libera o rótulo para reuso.
 */
@Entity
@Table(name = "restaurant_tables")
data class RestaurantTable(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(nullable = false, length = 40)
    var label: String,

    @Column(nullable = false)
    var seats: Int = 4,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,

    @Column(nullable = false)
    var active: Boolean = true,

    @Version
    @Column(nullable = false)
    var version: Long = 0,

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
