package com.menuflow.model

import jakarta.persistence.*
import java.util.UUID

/**
 * Opção dentro de um [ProductOptionGroup] (ex.: "Bacon" +R$3, "Mal passado").
 * priceCents é o ADICIONAL em centavos (pode ser 0). FK direta para o grupo.
 */
@Entity
@Table(name = "product_options")
data class ProductOption(
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,
    @Column(name = "group_id", nullable = false) var groupId: UUID,
    @Column(nullable = false) var name: String,
    @Column(name = "price_cents", nullable = false) var priceCents: Long = 0,
    @Column(nullable = false) var active: Boolean = true,
    @Column(name = "display_order", nullable = false) var displayOrder: Int = 0,
)
