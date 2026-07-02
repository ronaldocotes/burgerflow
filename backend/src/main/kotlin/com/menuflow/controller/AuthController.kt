package com.menuflow.controller

import com.menuflow.dto.LoginRequest
import com.menuflow.dto.LogoutRequest
import com.menuflow.dto.RefreshRequest
import com.menuflow.dto.TokenResponse
import com.menuflow.dto.TotpConfirmRequest
import com.menuflow.dto.TotpSetupResponse
import com.menuflow.dto.TwoFactorVerifyRequest
import com.menuflow.security.SecurityUtils
import com.menuflow.service.AuthService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/auth")
class AuthController(private val authService: AuthService) {

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): ResponseEntity<TokenResponse> =
        ResponseEntity.ok(authService.login(request))

    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody request: RefreshRequest): ResponseEntity<TokenResponse> =
        ResponseEntity.ok(authService.refresh(request))

    /** Revoga o refresh token apresentado. Idempotente; sempre 204. */
    @PostMapping("/logout")
    fun logout(@Valid @RequestBody request: LogoutRequest): ResponseEntity<Void> {
        authService.logout(request)
        return ResponseEntity.noContent().build()
    }

    // ── 2FA / TOTP ────────────────────────────────────────────────────────────

    /**
     * Verificacao do codigo TOTP apos login retornar status="2FA_REQUIRED".
     * Publico: o sessionToken autentica a sessao intermediaria (sem Bearer JWT).
     */
    @PostMapping("/2fa/verify")
    fun verifyTwoFactor(@Valid @RequestBody request: TwoFactorVerifyRequest): ResponseEntity<TokenResponse> =
        ResponseEntity.ok(authService.verifyTwoFactor(request))

    /**
     * Inicia o setup de 2FA para o usuario autenticado — retorna o URI otpauth://
     * para gerar o QR code no front. Restrito a SUPER_ADMIN.
     */
    @GetMapping("/2fa/setup")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    fun setupTotp(): ResponseEntity<TotpSetupResponse> {
        val userId = SecurityUtils.currentPrincipalOrThrow().userId
        return ResponseEntity.ok(authService.setupTotp(userId))
    }

    /**
     * Confirma o setup verificando o primeiro codigo TOTP. Ativa o 2FA se correto.
     * Restrito a SUPER_ADMIN.
     */
    @PostMapping("/2fa/confirm")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    fun confirmTotp(@Valid @RequestBody request: TotpConfirmRequest): ResponseEntity<Map<String, Boolean>> {
        val userId = SecurityUtils.currentPrincipalOrThrow().userId
        val activated = authService.confirmTotp(userId, request.code)
        return ResponseEntity.ok(mapOf("activated" to activated))
    }
}
