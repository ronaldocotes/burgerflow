package com.menuflow.security

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Service
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey

/**
 * Issues and validates HS256 JWTs. Claims carried: subject = userId,
 * tenantId (slug), tenantUuid, roles, and a token "type" (access|refresh).
 */
@Service
class JwtService(private val props: JwtProperties) {

    private val key: SecretKey = Keys.hmacShaKeyFor(props.secret.toByteArray())

    fun issueAccessToken(
        userId: UUID,
        tenantSlug: String,
        tenantUuid: UUID,
        roles: List<String>,
    ): String = build("access", userId, tenantSlug, tenantUuid, roles, props.accessTtlSeconds)

    fun issueRefreshToken(
        userId: UUID,
        tenantSlug: String,
        tenantUuid: UUID,
        roles: List<String>,
    ): String = build("refresh", userId, tenantSlug, tenantUuid, roles, props.refreshTtlSeconds)

    private fun build(
        type: String,
        userId: UUID,
        tenantSlug: String,
        tenantUuid: UUID,
        roles: List<String>,
        ttlSeconds: Long,
    ): String {
        val now = Date()
        return Jwts.builder()
            .issuer(props.issuer)
            .subject(userId.toString())
            // Unique token id: guarantees two tokens issued in the same second with
            // identical claims are still byte-distinct (so their SHA-256 hashes in
            // the refresh_tokens table never collide on the UNIQUE constraint), and
            // it strengthens rotation (each refresh token is individually identifiable).
            .id(UUID.randomUUID().toString())
            .claim("type", type)
            .claim("tenantId", tenantSlug)
            .claim("tenantUuid", tenantUuid.toString())
            .claim("roles", roles)
            .issuedAt(now)
            .expiration(Date(now.time + ttlSeconds * 1000))
            .signWith(key)
            .compact()
    }

    /** Parses & verifies signature/expiry. Throws JwtException on any problem. */
    fun parse(token: String): Claims =
        Jwts.parser()
            .verifyWith(key)
            .requireIssuer(props.issuer)
            .build()
            .parseSignedClaims(token)
            .payload

    @Suppress("UNCHECKED_CAST")
    fun rolesOf(claims: Claims): List<String> =
        (claims["roles"] as? List<String>) ?: emptyList()
}
