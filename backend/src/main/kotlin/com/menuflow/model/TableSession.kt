package com.menuflow.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Comanda: agrupa os pedidos de uma mesa entre abrir e fechar a conta. Vive no
 * banco do TENANT. Ciclo de vida: OPEN -> BILLING -> CLOSED. No máximo uma sessão
 * ativa (não-CLOSED) por mesa (índice parcial em V12 + checagem no serviço).
 */
@Entity
@Table(name = "table_sessions")
data class TableSession(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "table_id", nullable = false)
    var table: RestaurantTable,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var status: TableSessionStatus = TableSessionStatus.OPEN,

    @Column(name = "opened_at", nullable = false, updatable = false)
    val openedAt: Instant = Instant.now(),

    @Column(name = "opened_by_user_id")
    var openedByUserId: UUID? = null,

    @Column(name = "bill_requested_at")
    var billRequestedAt: Instant? = null,

    @Column(name = "closed_at")
    var closedAt: Instant? = null,

    @Column(name = "closed_by_user_id")
    var closedByUserId: UUID? = null,

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

enum class TableSessionStatus {
    OPEN,
    BILLING,
    CLOSED,
}
