package com.menuflow.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * A persisted, revocable refresh token (Sprint 2). Lives in the TENANT database.
 *
 * Only the SHA-256 hash of the token is stored ([tokenHash]) — never the raw
 * token — so a database leak does not yield usable tokens. On refresh the token
 * must exist, not be [revoked], and not be past [expiresAt]; logout flips
 * [revoked] to true. The unique constraint on [tokenHash] also makes lookup O(1).
 */
@Entity
@Table(name = "refresh_tokens")
data class RefreshToken(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    /** SHA-256 hex of the raw refresh token. The raw token is never stored. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    var tokenHash: String,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,

    @Column(name = "revoked", nullable = false)
    var revoked: Boolean = false,

    /** Tenant UUID (from the signed JWT) for audit; not an isolation key. */
    @Column(name = "tenant_id", nullable = false)
    var tenantId: UUID,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,
) {
    fun isUsable(now: Instant): Boolean = !revoked && expiresAt.isAfter(now)
}
