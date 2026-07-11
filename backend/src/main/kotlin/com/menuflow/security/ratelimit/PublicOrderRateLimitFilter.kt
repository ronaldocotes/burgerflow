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
        // Cobre criar pedido publico e a pre-checagem de cupom (Fase 3.2) — ambos
        // sao writes/leituras publicas passiveis de abuso (enumeracao de cupom).
        // Inclui a cotacao de frete (Fase 1 delivery): cada hit dispara um geocode
        // (custo externo Google) -> limitar por IP e a defesa-base contra flood.
        // Inclui o webhook do bot WhatsApp (Fase 4.3): rota publica adivinhavel; o
        // throttle por IP e a defesa-base contra flood/abuso de envio.
        val isPublicWrite = request.method == "POST" &&
            uri.contains("/public/") &&
            (uri.endsWith("/orders") || uri.endsWith("/apply-coupon") ||
                uri.endsWith("/delivery-quote") ||
                uri.endsWith("/whatsapp-opt-out") || uri.endsWith("/whatsapp/webhook"))
        // Clique de tracking (Fase 3.6): GET /public/{slug}/r/{trackingSlug} grava um
        // evento + incrementa o contador a cada hit -> vetor de click-fraud/flood,
        // limitado por IP como os writes publicos.
        val isTrackingClick = request.method == "GET" &&
            uri.contains("/public/") && uri.contains("/r/")
        // Resolucao de link/QR do cardapio (issue #11): GET /public/{slug}/l/{linkSlug}
        // faz um read no banco do tenant por hit -> rota publica adivinhavel, limitada
        // por IP como o clique de tracking.
        val isMenuLinkResolve = request.method == "GET" &&
            uri.contains("/public/") && uri.contains("/l/")
        // Auto-cadastro do motoboy (Fase C1 / auditoria M2): o token do path e uma
        // "senha" sujeita a forca bruta e o POST grava chave PIX de repasse — GET
        // (preview) e POST (conclusao) limitados por IP como os demais publicos.
        val isDriverSignup = uri.contains("/public/") && uri.contains("/motoboy/cadastro/")
        return !(isPublicWrite || isTrackingClick || isMenuLinkResolve || isDriverSignup)
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
