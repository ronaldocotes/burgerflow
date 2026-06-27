package com.menuflow.security.ratelimit

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Rate-limit do endpoint PUBLICO de pedidos (POST /public/{slug}/orders), o unico
 * write nao autenticado: qualquer um com o slug pode inundar. Default: 20 / 60s por IP.
 * backend=redis compartilha o contador entre instancias; memory (default) e node-local.
 * Desligue com menuflow.rate-limit.public-orders.enabled=false (suite de testes).
 */
@ConfigurationProperties(prefix = "menuflow.rate-limit.public-orders")
data class PublicOrderRateLimitProperties(
    val enabled: Boolean = true,
    val capacity: Long = 20,
    val refillPeriodSeconds: Long = 60,
    val backend: String = "memory",
)
