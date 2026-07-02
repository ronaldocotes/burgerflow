package com.menuflow.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Variante de link/QR do cardapio (issue #11). Vive no banco do TENANT
 * (db-per-tenant). Cada variante tem um slug publico editavel e um modo:
 *  - FULL      : cardapio completo COM pedido (link de delivery)
 *  - VIEW_ONLY : apenas visualizacao, SEM pedido (vitrine)
 *  - COUNTER   : pedido de balcao/mesa (pode apontar para uma mesa via [tableId])
 * Serve para diferenciar QR de mesa x QR de vitrine x link de delivery.
 */
@Entity
@Table(name = "menu_links")
data class MenuLink(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    /** Slug publico editavel (compoe /public/{tenant}/l/{slug}). Unico entre ativos. */
    @Column(name = "slug", nullable = false, length = 60)
    var slug: String,

    @Column(name = "variant", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    var variant: MenuLinkVariant,

    /** Rotulo interno para o dono identificar o link. */
    @Column(name = "label", nullable = false, length = 80)
    var label: String,

    /** Mesa vinculada quando variant=COUNTER (opcional). */
    @Column(name = "table_id")
    var tableId: UUID? = null,

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

enum class MenuLinkVariant {
    FULL,
    VIEW_ONLY,
    COUNTER,
}
