package com.menuflow.security.ratelimit

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Login rate-limit configuration (Sprint 2). Default: 5 attempts / 60s per IP.
 * Set `menuflow.rate-limit.login.enabled=false` to disable (used in most
 * integration tests so MockMvc's fixed 127.0.0.1 IP does not trip the limit).
 */
@ConfigurationProperties(prefix = "menuflow.rate-limit.login")
data class RateLimitProperties(
    val enabled: Boolean = true,
    val capacity: Long = 5,
    val refillPeriodSeconds: Long = 60,
)
