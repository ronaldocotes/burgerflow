package com.menuflow.config

import com.menuflow.security.JwtAuthFilter
import org.springframework.beans.factory.annotation.Qualifier
import com.menuflow.security.ratelimit.LoginRateLimitFilter
import com.menuflow.security.ratelimit.LoginRateLimiter
import com.menuflow.security.ratelimit.PublicOrderRateLimitFilter
import com.menuflow.security.ratelimit.PublicOrderRateLimitProperties
import com.menuflow.security.ratelimit.RateLimitProperties
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.core.annotation.Order
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.util.matcher.AntPathRequestMatcher
import org.springframework.security.web.util.matcher.OrRequestMatcher

/**
 * Stateless JWT security. The servlet context-path is api-v1, so the matchers
 * below are RELATIVE to it (the auth matcher maps to the full context path).
 * Everything except auth, health and swagger requires a valid access token.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig(
    private val jwtAuthFilter: JwtAuthFilter,
    @Qualifier("loginRateLimiter") private val loginRateLimiter: LoginRateLimiter,
    private val rateLimitProperties: RateLimitProperties,
    @Qualifier("publicOrderRateLimiter") private val publicOrderRateLimiter: LoginRateLimiter,
    private val publicOrderRateLimitProperties: PublicOrderRateLimitProperties,
) {

    /**
     * Dedicated high-priority chain for the WebSocket handshake. The bare upgrade
     * carries no business data; authentication happens on the STOMP CONNECT frame
     * (JWT) in WebSocketConfig. An AntPathRequestMatcher is used (not the default
     * MVC matcher) because the WS endpoint is not an MVC handler and the MVC
     * matcher does not resolve its path under a servlet context-path.
     */
    @Bean
    @Order(1)
    fun webSocketSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            // The real server presents the request to Spring Security WITH the
            // servlet context-path (/api/v1/ws); MockMvc presents it without it
            // (/ws). Match both so the handshake is permitted in every environment.
            .securityMatcher(
                OrRequestMatcher(
                    AntPathRequestMatcher("/ws"),
                    AntPathRequestMatcher("/ws/**"),
                    AntPathRequestMatcher("/ws-sockjs/**"),
                    AntPathRequestMatcher("/api/v1/ws"),
                    AntPathRequestMatcher("/api/v1/ws/**"),
                    AntPathRequestMatcher("/api/v1/ws-sockjs/**"),
                ),
            )
            .csrf { it.disable() }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
        return http.build()
    }

    @Bean
    @Order(2)
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        val loginRateLimitFilter = LoginRateLimitFilter(loginRateLimiter, rateLimitProperties)
        val publicOrderRateLimitFilter = PublicOrderRateLimitFilter(publicOrderRateLimiter, publicOrderRateLimitProperties)
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { reg ->
                reg
                    .requestMatchers("/auth/**").permitAll()
                    .requestMatchers("/actuator/health").permitAll()
                    .requestMatchers("/public/**").permitAll()
                    // Webhook do Asaas (PIX): publico, sem JWT. O tenant vem do path
                    // {tenantSlug}; defesa em PixPaymentService (ver controller).
                    .requestMatchers("/webhooks/**").permitAll()
                    .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                    .anyRequest().authenticated()
            }
            // Unauthenticated access to a protected route -> 401 (not 403). 403 is
            // reserved for an authenticated user lacking the required role.
            .exceptionHandling { it.authenticationEntryPoint(unauthorizedEntryPoint()) }
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
            // Rate-limit login BEFORE auth runs, so throttling precedes any
            // password verification (brute-force defense).
            .addFilterBefore(loginRateLimitFilter, JwtAuthFilter::class.java)
            // Throttle do endpoint publico de pedidos (20/IP/min), antes do banco.
            .addFilterBefore(publicOrderRateLimitFilter, JwtAuthFilter::class.java)
        return http.build()
    }

    @Bean
    fun unauthorizedEntryPoint(): AuthenticationEntryPoint =
        AuthenticationEntryPoint { _, response, _ ->
            response.setHeader("WWW-Authenticate", "Bearer")
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication required")
        }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder(12)
}
