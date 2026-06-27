package com.menuflow.model

import jakarta.persistence.*
import java.util.UUID

/**
 * Grupo de complementos de um produto (ex.: "Ponto da carne", "Adicionais").
 * Vive no banco do TENANT; isolamento é físico (1 DB por tenant), por isso a FK
 * é um UUID direto (productId), não @ManyToOne — mesmo padrão de ProductSize.
 *
 * minSelect/maxSelect definem a regra de seleção: minSelect>=1 torna o grupo
 * obrigatório; maxSelect=1 é escolha única, maxSelect>1 é múltipla escolha.
 */
@Entity
@Table(name = "product_option_groups")
data class ProductOptionGroup(
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,
    @Column(name = "product_id", nullable = false) var productId: UUID,
    @Column(nullable = false) var name: String,
    @Column(name = "min_select", nullable = false) var minSelect: Int = 0,
    @Column(name = "max_select", nullable = false) var maxSelect: Int = 1,
    @Column(nullable = false) var active: Boolean = true,
    @Column(name = "display_order", nullable = false) var displayOrder: Int = 0,
)
