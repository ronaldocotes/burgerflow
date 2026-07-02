package com.menuflow.service

import com.menuflow.dto.LoginRequest
import com.menuflow.dto.LogoutRequest
import com.menuflow.dto.RefreshRequest
import com.menuflow.dto.TokenResponse
import com.menuflow.dto.TotpSetupResponse
import com.menuflow.dto.TwoFactorVerifyRequest
import com.menuflow.exception.UnauthorizedException
import com.menuflow.model.control.Tenant
import com.menuflow.model.control.User
import com.menuflow.model.control.UserRole
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
 * Autenticacao contra o banco de CONTROLE. Login resolve o tenant pelo slug, depois
 * o usuario por (tenantId, email). Todas as falhas retornam o MESMO 401 (anti-enumeracao).
 *
 * Sprint 2: refresh tokens sao PERSISTIDOS e REVOGIVEIS no banco do tenant. No refresh,
 * o token e validado contra o store e ROTACIONADO (antigo revogado, novo emitido + guardado).
 *
 * F3: SUPER_ADMINs com TOTP ativo recebem status="2FA_REQUIRED" no login normal.
 * O JWT so e emitido apos POST /auth/2fa/verify com o codigo TOTP valido.
 * NOTA DE PRODUCAO: o segredo TOTP fica em memoria enquanto nao houver V15 migration
 * (ALTER TABLE users ADD COLUMN totp_secret VARCHAR(256)) — lost on restart em prod.
 */
@Service
class AuthService(
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
    private val jwtProps: JwtProperties,
    private val refreshTokenService: RefreshTokenService,
    private val totpService: TotpService,
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

        // SUPER_ADMINs com 2FA ativo recebem uma sessao intermediaria.
        // O JWT so e emitido apos verificar o codigo TOTP em /auth/2fa/verify.
        if (user.role == UserRole.SUPER_ADMIN && totpService.hasSecret(user.id!!)) {
            val sessionToken = totpService.createSession(user.id!!)
            return TokenResponse(
                token = "",
                refreshToken = "",
                expiresIn = 0,
                status = "2FA_REQUIRED",
                sessionToken = sessionToken,
            )
        }

        return issueAndStoreTokens(user, tenant)
    }

    /**
     * Verifica o codigo TOTP da sessao intermediaria e emite o JWT completo.
     * Chamado por POST /auth/2fa/verify apos o login retornar status="2FA_REQUIRED".
     */
    @Transactional("controlTransactionManager")
    fun verifyTwoFactor(request: TwoFactorVerifyRequest): TokenResponse {
        val userId = totpService.resolveSession(request.sessionToken)
            ?: throw UnauthorizedException("Sessao 2FA invalida ou expirada")

        if (!totpService.verify(userId, request.code)) {
            throw UnauthorizedException("Codigo TOTP invalido")
        }

        val user = userRepository.findById(userId).orElse(null)
            ?.takeIf { it.isActive }
            ?: throw UnauthorizedException("Usuario nao encontrado")
        val tenant = tenantRepository.findById(user.tenantId).orElse(null)
            ?.takeIf { it.isActive }
            ?: throw UnauthorizedException("Tenant nao encontrado")

        return issueAndStoreTokens(user, tenant)
    }

    /**
     * Inicia o setup de 2FA para o usuario autenticado no SecurityContext.
     * Retorna o URI otpauth:// para gerar o QR code no front.
     * Chamado por GET /auth/2fa/setup (requer autenticacao).
     */
    fun setupTotp(userId: UUID): TotpSetupResponse = totpService.startSetup(userId)

    /**
     * Confirma o setup de 2FA verificando o primeiro codigo do autenticador.
     * Ativa o TOTP para o usuario se o codigo for valido.
     * Chamado por POST /auth/2fa/confirm (requer autenticacao).
     */
    fun confirmTotp(userId: UUID, code: String): Boolean = totpService.confirmSetup(userId, code)

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

    /**
     * Emite e persiste tokens para um usuário já autenticado por OUTRO meio (ex.:
     * aceite de convite, onde a posse de um token de convite válido prova a
     * identidade). Reusa o mesmo caminho do login (refresh persistido no tenant).
     */
    @Transactional("controlTransactionManager")
    fun issueSession(user: User, tenant: Tenant): TokenResponse = issueAndStoreTokens(user, tenant)

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
