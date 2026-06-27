package com.menuflow.config

import com.menuflow.security.AuthPrincipal
import com.menuflow.security.JwtService
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.messaging.support.MessageHeaderAccessor
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer
import java.util.UUID

/**
 * STOMP-over-WebSocket for the KDS (Sprint 2). Clients connect to `/ws` and
 * subscribe to `/topic/...` to receive live order/delivery events.
 *
 * SECURITY: the WebSocket is protected by the SAME signed JWT as the REST API.
 * The token is read from the STOMP CONNECT frame `Authorization: Bearer ...`
 * header, verified, and the tenant is bound from the SIGNED `tenantId` claim —
 * never from a client-supplied value. A client therefore can only ever subscribe
 * while authenticated, and the server only publishes to its own tenant topic
 * (`/topic/kds/{tenantSlug}`), so cross-tenant eavesdropping is not possible by
 * topic-name guessing alone (you still need a valid token; the publish side is
 * keyed by the publisher's tenant — see KdsEventPublisher).
 */
@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig(
    private val jwtService: JwtService,
) : WebSocketMessageBrokerConfigurer {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        // Native WebSocket endpoint (raw ws:// upgrade at /ws).
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*")
        // SockJS fallback for browsers that cannot use raw WebSocket, on a
        // distinct path so its /ws-sockjs/** handler mappings do not shadow the
        // native /ws upgrade above.
        registry.addEndpoint("/ws-sockjs").setAllowedOriginPatterns("*").withSockJS()
    }

    override fun configureMessageBroker(registry: MessageBrokerRegistry) {
        registry.enableSimpleBroker("/topic")
        registry.setApplicationDestinationPrefixes("/app")
    }

    /**
     * Authenticates the STOMP CONNECT using the Bearer JWT and rejects the
     * connection if absent/invalid. The authenticated [AuthPrincipal] is attached
     * to the session so downstream handlers know the tenant.
     */
    override fun configureClientInboundChannel(registration: ChannelRegistration) {
        registration.interceptors(object : ChannelInterceptor {
            override fun preSend(message: Message<*>, channel: MessageChannel): Message<*>? {
                val accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor::class.java)
                    ?: return message
                if (StompCommand.CONNECT == accessor.command) {
                    val authHeader = accessor.getFirstNativeHeader("Authorization")
                    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                        throw IllegalArgumentException("Missing bearer token on STOMP CONNECT")
                    }
                    val claims = jwtService.parse(authHeader.substring(7))
                    if (claims["type"] != "access") {
                        throw IllegalArgumentException("Not an access token")
                    }
                    val principal = AuthPrincipal(
                        userId = UUID.fromString(claims.subject),
                        tenantSlug = claims["tenantId"] as String,
                        tenantUuid = UUID.fromString(claims["tenantUuid"] as String),
                        roles = jwtService.rolesOf(claims),
                    )
                    val auth = UsernamePasswordAuthenticationToken(
                        principal, null, principal.roles.map { SimpleGrantedAuthority("ROLE_$it") },
                    )
                    accessor.user = auth
                    log.debug("STOMP CONNECT authenticated for tenant {}", principal.tenantSlug)
                }

                // Tenant isolation on SUBSCRIBE: a client may only subscribe to its
                // OWN tenant topic. Without this, an authenticated user of tenant A
                // could subscribe to /topic/kds/<B> and eavesdrop on tenant B's
                // kitchen/delivery events (cross-tenant leak). The tenant slug is
                // taken from the SIGNED token on the session, never from the client.
                if (StompCommand.SUBSCRIBE == accessor.command) {
                    val principal = (accessor.user as? UsernamePasswordAuthenticationToken)?.principal as? AuthPrincipal
                        ?: throw IllegalArgumentException("Unauthenticated subscribe")
                    val destination = accessor.destination ?: ""
                    if (!isOwnTenantTopic(destination, principal.tenantSlug)) {
                        log.warn(
                            "Blocked cross-tenant SUBSCRIBE: tenant={} destination={}",
                            principal.tenantSlug, destination,
                        )
                        throw IllegalArgumentException("Forbidden subscription destination")
                    }
                }
                return message
            }
        })
    }

    /**
     * True only for this tenant's own topics, i.e. /topic/{kds|delivery}/{slug}
     * where {slug} == the authenticated tenant. Anything else is rejected.
     */
    private fun isOwnTenantTopic(destination: String, tenantSlug: String): Boolean {
        val allowed = setOf(
            "/topic/kds/$tenantSlug",
            "/topic/delivery/$tenantSlug",
            "/topic/tables/$tenantSlug",
        )
        return destination in allowed
    }
}
