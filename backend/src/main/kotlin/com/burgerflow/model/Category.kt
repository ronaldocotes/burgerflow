package com.burgerflow.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "categories")
data class Category(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(nullable = false, unique = true)
    var name: String,

    @Column(nullable = false)
    var description: String = "",

    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0,

    /** Soft-delete flag. */
    @Column(name = "active", nullable = false)
    var active: Boolean = true,

    @Column(name = "color_code")
    var colorCode: String? = null,

    @Column(name = "icon_url")
    var iconUrl: String? = null,

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
