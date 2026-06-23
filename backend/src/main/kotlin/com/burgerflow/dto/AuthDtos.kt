package com.burgerflow.dto

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

data class TokenResponse(
    val token: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long,
)
