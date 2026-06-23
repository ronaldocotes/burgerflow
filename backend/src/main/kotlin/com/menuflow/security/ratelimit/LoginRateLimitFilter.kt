package com.menuflow.security.ratelimit

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Rate-limits login attempts per client IP (Sprint 2): default 5 / minute. On
 * exceed, responds 429 with `Retry-After` and `X-RateLimit-*` headers, BEFORE the
 * auth machinery runs (so a brute-forcer cannot even reach password verification).
 *
 * The client IP prefers the first `X-Forwarded-For` entry (we sit behind a
 * proxy/LB in prod); falls back to the socket remote address. Disable via
 * `menuflow.rate-limit.login.enabled=false` (most integration tests do, since
 * MockMvc always reports 127.0.0.1 and would otherwise share one bucket).
 */
class LoginRateLimitFilter(
    private val limiter: LoginRateLimiter,
    private val props: RateLimitProperties,
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        if (!props.enabled) return true
        // Match on the request URI suffix so it works both with the real servlet
        // context-path (/api/v1/auth/login) and under MockMvc (which reports
        // /auth/login with an empty servletPath).
        val isLogin = request.method == "POST" && request.requestURI.endsWith("/auth/login")
        return !isLogin
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val ip = clientIp(request)
        val decision = limiter.tryConsume(ip)
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
            """{"status":429,"code":"RATE_LIMITED","message":"Too many login attempts. Try again later."}""",
        )
    }

    private fun clientIp(request: HttpServletRequest): String {
        val xff = request.getHeader("X-Forwarded-For")
        if (!xff.isNullOrBlank()) return xff.split(",").first().trim()
        return request.remoteAddr ?: "unknown"
    }
}
