package com.menuflow.security

import com.menuflow.tenant.TenantContext
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Authenticates a request from its Bearer access token and — crucially — binds
 * the tenant to [TenantContext] from the SIGNED token claim, not from a
 * client-supplied header. This makes cross-tenant access via header spoofing
 * impossible for authenticated routes: a user's JWT can only ever reach its own
 * tenant database.
 */
@Component
class JwtAuthFilter(
    private val jwtService: JwtService,
    private val tenantStatusCache: TenantStatusCache,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val header = request.getHeader("Authorization")
        if (header != null && header.startsWith("Bearer ")) {
            val token = header.substring(7)
            try {
                val claims = jwtService.parse(token)
                if (claims["type"] != "access") {
                    // refresh tokens must not authenticate business requests
                    throw IllegalArgumentException("Not an access token")
                }
                val userId = UUID.fromString(claims.subject)
                val tenantSlug = claims["tenantId"] as String
                val tenantUuid = UUID.fromString(claims["tenantUuid"] as String)
                val roles = jwtService.rolesOf(claims)

                // Tenant desativado no painel super-admin corta o acesso mesmo com
                // access token ainda válido (login/refresh já recusam; aqui é o
                // enforcement stateless, cache 60s p/ não bater no banco por request).
                if (!tenantStatusCache.isActive(tenantSlug)) {
                    throw IllegalStateException("Tenant is not active")
                }

                val principal = AuthPrincipal(userId, tenantSlug, tenantUuid, roles)
                val authorities = roles.map { SimpleGrantedAuthority("ROLE_$it") }
                val auth = UsernamePasswordAuthenticationToken(principal, null, authorities)
                SecurityContextHolder.getContext().authentication = auth

                // Bind tenant from the signed token (authoritative).
                TenantContext.set(tenantSlug)
            } catch (ex: Exception) {
                // Invalid/expired/forged token: leave context empty -> 401 downstream.
                log.debug("Rejected JWT: {}", ex.message)
                SecurityContextHolder.clearContext()
            }
        }

        try {
            filterChain.doFilter(request, response)
        } finally {
            TenantContext.clear()
        }
    }
}
