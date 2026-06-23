package com.menuflow.security

import java.util.UUID

/**
 * Authenticated principal placed in the SecurityContext by [JwtAuthFilter].
 * Carries the tenant binding so handlers/services can scope by tenant.
 */
data class AuthPrincipal(
    val userId: UUID,
    val tenantSlug: String,
    val tenantUuid: UUID,
    val roles: List<String>,
)
