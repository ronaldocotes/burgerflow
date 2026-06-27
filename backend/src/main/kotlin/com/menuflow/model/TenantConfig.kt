package com.menuflow.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Configurações operacionais do tenant. Vive no banco do TENANT (db-per-tenant),
 * então há no máximo uma linha por restaurante — não precisa de coluna de escopo.
 *
 * Hoje guarda apenas o aceite automático de pedidos (pedido nasce em PREPARING,
 * indo direto para a cozinha sem ação manual). A tabela foi desenhada para
 * crescer: novos toggles entram como colunas aditivas nesta mesma linha.
 */
@Entity
@Table(name = "tenant_config")
data class TenantConfig(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    /** Pedido novo nasce em PREPARING (já na cozinha) em vez de PENDING. */
    @Column(name = "auto_accept_orders", nullable = false)
    var autoAcceptOrders: Boolean = false,

    /** Chave PIX estatica do restaurante (nullable: pode nao ter PIX). */
    @Column(name = "pix_key", nullable = true, length = 140)
    var pixKey: String? = null,

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
