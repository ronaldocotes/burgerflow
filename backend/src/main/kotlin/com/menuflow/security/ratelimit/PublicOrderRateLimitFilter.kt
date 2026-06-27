package com.menuflow.security.ratelimit

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Limita a criacao de pedidos publicos por IP (default 20/min). Espelha
 * [LoginRateLimitFilter] mas mira POST /public/{slug}/orders. Em excesso responde
 * 429 com Retry-After / X-RateLimit-* ANTES de tocar o banco do tenant.
 *
 * O IP prefere o primeiro X-Forwarded-For (atras de proxy/LB em prod); cai para o
 * remoteAddr. Desligue via menuflow.rate-limit.public-orders.enabled=false (MockMvc
 * sempre reporta 127.0.0.1 e dividiria um unico bucket entre as classes de teste).
 */
class PublicOrderRateLimitFilter(
    private val limiter: LoginRateLimiter,
    private val props: PublicOrderRateLimitProperties,
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        if (!props.enabled) return true
        // Casa tanto sob o context-path real (/api/v1/public/{slug}/orders) quanto
        // sob MockMvc (/public/{slug}/orders).
        val uri = request.requestURI
        val isPublicOrder = request.method == "POST" &&
            uri.contains("/public/") && uri.endsWith("/orders")
        return !isPublicOrder
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val ip = clientIp(request)
        val decision = limiter.tryConsume("public-order:$ip")
        if (decision.allowed) {
            filterChain.doFilter(request, response)
            return
        }
        response.status = HttpStatus.TOO_MANY_REQUESTS.value()
        response.setHeader("Retry-After", decision.retryAfterSeconds.toString())
        response.setHeader("X-RateLimit-Limit", props.capacity.toString())
        response.setHeader("X-RateLimit-Remaining", "0")
        response.contentType = "application/json"
        response.writer.write(
            """{"status":429,"code":"RATE_LIMITED","message":"Too many orders. Try again later."}""",
        )
    }

    private fun clientIp(request: HttpServletRequest): String {
        val xff = request.getHeader("X-Forwarded-For")
        if (!xff.isNullOrBlank()) return xff.split(",").first().trim()
        return request.remoteAddr ?: "unknown"
    }
}
