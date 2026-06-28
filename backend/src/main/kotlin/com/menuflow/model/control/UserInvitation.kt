package com.menuflow.model.control

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Convite de usuário, no banco de CONTROLE. Guarda apenas o HASH SHA-256 do token
 * (o token cru só aparece UMA vez, no link devolvido a quem convida). Escopado por
 * [tenantId]; ao aceitar, cria/ativa o User correspondente em (tenantId, email).
 *
 * Invariante de unicidade de convite PENDENTE por (tenant, email) e de token é
 * garantida por índices no banco (V4__user_invitations.sql).
 */
@Entity
@Table(name = "user_invitations")
class UserInvitation(
    @Column(name = "tenant_id", nullable = false)
    val tenantId: UUID,

    @Column(nullable = false)
    var email: String,

    @Column(name = "token_hash", nullable = false, length = 128)
    val tokenHash: String,

    @Column(name = "invited_by_user_id", nullable = false)
    val invitedByUserId: UUID,

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var role: UserRole = UserRole.STAFF,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var status: InvitationStatus = InvitationStatus.PENDING,

    @Column(name = "accepted_at")
    var acceptedAt: Instant? = null,

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)

enum class InvitationStatus {
    PENDING,
    ACCEPTED,
    REVOKED,
    EXPIRED,
}
