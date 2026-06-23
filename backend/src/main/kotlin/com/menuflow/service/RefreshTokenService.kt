package com.menuflow.service

import com.menuflow.exception.UnauthorizedException
import com.menuflow.model.RefreshToken
import com.menuflow.repository.tenant.RefreshTokenRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Persists and revokes refresh tokens in the TENANT database (Sprint 2).
 *
 * Only the SHA-256 hash of the raw refresh JWT is stored — never the token
 * itself. Refresh validates existence + not-revoked + not-expired; logout and
 * rotation revoke. The caller MUST have bound TenantContext to the correct tenant
 * (AuthService does this from the slug resolved at login / from the signed claim
 * on refresh) before invoking these methods, since they hit the routed tenant DB.
 */
@Service
class RefreshTokenService(
    private val repository: RefreshTokenRepository,
) {

    fun hash(rawToken: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(rawToken.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Records a freshly issued refresh token. */
    @Transactional("tenantTransactionManager")
    fun store(rawToken: String, userId: UUID, tenantUuid: UUID, expiresAt: Instant) {
        repository.save(
            RefreshToken(
                userId = userId,
                tokenHash = hash(rawToken),
                expiresAt = expiresAt,
                tenantId = tenantUuid,
            ),
        )
    }

    /**
     * Validates a refresh token against the store and returns the row. Throws 401
     * if it is unknown, revoked or expired. Read within the tenant tx.
     */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun validate(rawToken: String): RefreshToken {
        val row = repository.findByTokenHash(hash(rawToken))
            ?: throw UnauthorizedException("Invalid refresh token")
        if (!row.isUsable(Instant.now())) {
            throw UnauthorizedException("Refresh token revoked or expired")
        }
        return row
    }

    /** Revokes a refresh token by its raw value. No-op if unknown (idempotent logout). */
    @Transactional("tenantTransactionManager")
    fun revoke(rawToken: String) {
        val row = repository.findByTokenHash(hash(rawToken)) ?: return
        if (!row.revoked) {
            row.revoked = true
            row.revokedAt = Instant.now()
            repository.save(row)
        }
    }
}
