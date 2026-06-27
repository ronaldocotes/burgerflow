package com.menuflow.model

import jakarta.persistence.*
import java.util.UUID

/**
 * SNAPSHOT de um complemento escolhido em um item de pedido. Guarda nome do grupo,
 * nome da opção e preço NO MOMENTO do pedido — alterar/excluir a opção do catálogo
 * depois não muda pedidos antigos (domínio: snapshot de pedido). Dinheiro em centavos.
 */
@Entity
@Table(name = "order_item_options")
data class OrderItemOption(
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,
    @Column(name = "order_item_id", nullable = false) var orderItemId: UUID,
    @Column(name = "option_id", nullable = false) var optionId: UUID,
    @Column(name = "group_name", nullable = false) var groupName: String,
    @Column(name = "option_name", nullable = false) var optionName: String,
    @Column(name = "price_cents", nullable = false) var priceCents: Long,
)
