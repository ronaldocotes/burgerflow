package com.menuflow.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Motivo de cancelamento pre-cadastrado (issue #10). Lista editavel usada no fluxo
 * de cancelar pedido do KDS/PDV em vez de texto livre — facilita relatorio de
 * motivo no DRE/growth. Vive no banco do TENANT (db-per-tenant). Motivo desativado
 * (active=false) some do seletor mas sobrevive nos pedidos historicos que o usaram.
 */
@Entity
@Table(name = "cancellation_reasons")
data class CancellationReason(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "description", nullable = false, length = 140)
    var description: String,

    @Column(name = "active", nullable = false)
    var active: Boolean = true,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,

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
