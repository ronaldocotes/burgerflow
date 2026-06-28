package com.menuflow.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Movimento manual de caixa (sangria/reforço) de um turno [CashSession]. Vive no
 * banco do TENANT. Valor SEMPRE positivo em centavos; o sinal no cálculo do
 * esperado vem do [type] (WITHDRAWAL subtrai, DEPOSIT soma).
 */
@Entity
@Table(name = "cash_session_entries")
data class CashSessionEntry(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "session_id", nullable = false)
    val sessionId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val type: CashEntryType,

    @Column(name = "amount_cents", nullable = false)
    val amountCents: Long,

    @Column
    var reason: String? = null,

    @Column(name = "created_by_user_id", nullable = false)
    val createdByUserId: UUID,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
