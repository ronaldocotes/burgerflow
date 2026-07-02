package com.menuflow.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Produto em destaque no pop-up de entrada do cardapio publico (issue #13).
 * Vive no banco do TENANT (db-per-tenant). O guard-rail de "ate 3" e aplicado
 * no EntryPopupService (Postgres nao expressa "max N linhas" sem trigger); a
 * UNIQUE(product_id) da migracao impede duplicar o mesmo produto no pop-up.
 */
@Entity
@Table(name = "entry_popup_products")
data class EntryPopupProduct(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "product_id", nullable = false)
    var productId: UUID,

    /** Ordem de exibicao no pop-up (0-based, definida pela ordem enviada no PUT). */
    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
