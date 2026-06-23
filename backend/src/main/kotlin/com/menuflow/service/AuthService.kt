package com.menuflow.service

import com.menuflow.dto.LoginRequest
import com.menuflow.dto.LogoutRequest
import com.menuflow.dto.RefreshRequest
import com.menuflow.dto.TokenResponse
import com.menuflow.exception.UnauthorizedException
import com.menuflow.model.control.Tenant
import com.menuflow.model.control.User
import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.control.UserRepository
import com.menuflow.security.JwtProperties
import com.menuflow.security.JwtService
import com.menuflow.tenant.TenantContext
import io.jsonwebtoken.JwtException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Authentication against the CONTROL database. Login resolves the tenant by slug,
 * then the user by (tenantId, email). All failure modes return the SAME 401 to
 * avoid user/tenant enumeration.
 *
 * Sprint 2: refresh tokens are now PERSISTED and REVOCABLE in the tenant DB. On
 * refresh the token is validated against the store and ROTATED (old revoked, new
 * issued + stored). Logout revokes the presented refresh token.
 *
 * The refresh-token store lives in the TENANT database, so each control-side
 * operation binds TenantContext to the resolved tenant slug around the
 * RefreshTokenService call, restoring the prior binding afterwards.
 */
@Service
class AuthService(
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
    private val jwtProps: JwtProperties,
    private val refreshTokenService: RefreshTokenService,
) {

    @Transactional("controlTransactionManager")
    fun login(request: LoginRequest): TokenResponse {
        val tenant = tenantRepository.findBySlug(request.tenantSlug)
            ?.takeIf { it.isActive }
            ?: throw UnauthorizedException("Invalid credentials")

        val user = userRepository.findByTenantIdAndEmail(tenant.id!!, request.email.lowercase())
            ?.takeIf { it.isActive }
            ?: throw UnauthorizedException("Invalid credentials")

        if (!passwordEncoder.matches(request.password, user.passwordHash)) {
            throw UnauthorizedException("Invalid credentials")
        }

        user.lastLoginAt = Instant.now()
        userRepository.save(user)

        return issueAndStoreTokens(user, tenant)
    }

    @Transactional("controlTransactionManager")
    fun refresh(request: RefreshRequest): TokenResponse {
        val claims = try {
            jwtService.parse(request.refreshToken)
        } catch (ex: JwtException) {
            throw UnauthorizedException("Invalid refresh token")
        }
        if (claims["type"] != "refresh") {
            throw UnauthorizedException("Invalid refresh token")
        }

        val userId = UUID.fromString(claims.subject)
        // Re-check the user is still active on refresh (defense in depth:
        // a disabled/removed user must lose access even with a valid refresh token).
        val user = userRepository.findById(userId).orElse(null)
            ?.takeIf { it.isActive }
            ?: throw UnauthorizedException("Invalid refresh token")

        val tenant = tenantRepository.findById(user.tenantId).orElse(null)
            ?.takeIf { it.isActive }
            ?: throw UnauthorizedException("Invalid refresh token")

        // Validate against the revocation store and ROTATE: a previously-revoked
        // token (after logout or a prior rotation/reuse) is rejected here even
        // though its JWT signature/expiry are still valid.
        withTenant(tenant.slug) {
            refreshTokenService.validate(request.refreshToken)
            refreshTokenService.revoke(request.refreshToken)
        }

        return issueAndStoreTokens(user, tenant)
    }

    @Transactional("controlTransactionManager")
    fun logout(request: LogoutRequest) {
        // Resolve the tenant from the signed refresh token so we revoke in the
        // right tenant DB. A malformed/unknown token is a silent no-op (idempotent).
        val claims = try {
            jwtService.parse(request.refreshToken)
        } catch (ex: JwtException) {
            return
        }
        if (claims["type"] != "refresh") return
        val tenantSlug = claims["tenantId"] as? String ?: return
        withTenant(tenantSlug) {
            refreshTokenService.revoke(request.refreshToken)
        }
    }

    private fun issueAndStoreTokens(user: User, tenant: Tenant): TokenResponse {
        val roles = listOf(user.role.name)
        val access = jwtService.issueAccessToken(user.id!!, tenant.slug, tenant.id!!, roles)
        val refresh = jwtService.issueRefreshToken(user.id!!, tenant.slug, tenant.id!!, roles)
        val refreshExpiry = Instant.now().plusSeconds(jwtProps.refreshTtlSeconds)
        withTenant(tenant.slug) {
            refreshTokenService.store(refresh, user.id!!, tenant.id!!, refreshExpiry)
        }
        return TokenResponse(
            token = access,
            refreshToken = refresh,
            expiresIn = jwtProps.accessTtlSeconds,
        )
    }

    /** Runs [block] with TenantContext bound to [slug], restoring the prior value. */
    private fun <T> withTenant(slug: String, block: () -> T): T {
        val previous = TenantContext.get()
        TenantContext.set(slug)
        try {
            return block()
        } finally {
            if (previous != null) TenantContext.set(previous) else TenantContext.clear()
        }
    }
}
