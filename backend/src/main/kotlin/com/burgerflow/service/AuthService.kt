package com.burgerflow.service

import com.burgerflow.dto.LoginRequest
import com.burgerflow.dto.RefreshRequest
import com.burgerflow.dto.TokenResponse
import com.burgerflow.exception.UnauthorizedException
import com.burgerflow.repository.control.TenantRepository
import com.burgerflow.repository.control.UserRepository
import com.burgerflow.security.JwtProperties
import com.burgerflow.security.JwtService
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
 */
@Service
class AuthService(
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
    private val jwtProps: JwtProperties,
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

        return issueTokens(user.id!!, tenant.slug, tenant.id!!, listOf(user.role.name))
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

        return issueTokens(user.id!!, tenant.slug, tenant.id!!, listOf(user.role.name))
    }

    private fun issueTokens(userId: UUID, slug: String, tenantUuid: UUID, roles: List<String>): TokenResponse {
        val access = jwtService.issueAccessToken(userId, slug, tenantUuid, roles)
        val refresh = jwtService.issueRefreshToken(userId, slug, tenantUuid, roles)
        return TokenResponse(
            token = access,
            refreshToken = refresh,
            expiresIn = jwtProps.accessTtlSeconds,
        )
    }
}
