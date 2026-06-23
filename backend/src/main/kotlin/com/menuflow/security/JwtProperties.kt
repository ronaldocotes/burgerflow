package com.menuflow.security

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "menuflow.jwt")
data class JwtProperties(
    /** HS256 secret. MUST be overridden via env in prod; >= 32 bytes. */
    val secret: String = "change-me-in-prod-menuflow-dev-secret-key-256bits!",
    /** Access token TTL in seconds (default 1h). */
    val accessTtlSeconds: Long = 3600,
    /** Refresh token TTL in seconds (default 7d). */
    val refreshTtlSeconds: Long = 604800,
    val issuer: String = "menuflow",
)
