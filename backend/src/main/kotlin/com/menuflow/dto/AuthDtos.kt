package com.menuflow.dto

import com.fasterxml.jackson.annotation.JsonInclude
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

data class LoginRequest(
    @field:NotBlank @field:Email
    val email: String,
    @field:NotBlank
    val password: String,
    @field:NotBlank
    val tenantSlug: String,
)

data class RefreshRequest(
    @field:NotBlank
    val refreshToken: String,
)

data class LogoutRequest(
    @field:NotBlank
    val refreshToken: String,
)

data class TokenResponse(
    val token: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long,
    /**
     * Preenchido APENAS quando o SUPER_ADMIN tem 2FA ativo. Valor: "2FA_REQUIRED".
     * Neste caso token/refreshToken sao strings vazias — o JWT sera emitido
     * em POST /auth/2fa/verify apos validar o codigo TOTP.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val status: String? = null,
    /**
     * Token de sessao intermediaria valido por 5 minutos. Enviado em POST /auth/2fa/verify.
     * Nunca persistido — fica em memoria no TotpService.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val sessionToken: String? = null,
)

// ── 2FA / TOTP ───────────────────────────────────────────────────────────────

/** Enviado em POST /auth/2fa/verify para completar o login de SUPER_ADMIN com 2FA. */
data class TwoFactorVerifyRequest(
    @field:NotBlank
    val sessionToken: String,
    @field:NotBlank
    val code: String,
)

/** Retornado em GET /auth/2fa/setup com o segredo para configurar o autenticador. */
data class TotpSetupResponse(
    /** URI no formato otpauth:// para gerar o QR code no front. */
    val qrUri: String,
    /** Segredo em Base32 para entrada manual no Google Authenticator / Authy. */
    val secret: String,
)

/** Confirmacao de setup enviada em POST /auth/2fa/confirm. */
data class TotpConfirmRequest(
    @field:NotBlank
    val code: String,
)
