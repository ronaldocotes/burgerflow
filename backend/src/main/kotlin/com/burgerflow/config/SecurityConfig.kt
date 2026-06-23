package com.burgerflow.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig(
    private val tenantIdentifierResolver: TenantIdentifierResolver
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { 
                it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) 
            }
            .authorizeHttpRequests { requests ->
                requests
                    // Public endpoints
                    .requestMatchers("/api/v1/auth/**").permitAll()
                    .requestMatchers("/api/v1/public/**").permitAll()
                    .requestMatchers("/actuator/health").permitAll()
                    
                    // Swagger
                    .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                    
                    // Admin endpoints
                    .requestMatchers(HttpMethod.GET, "/api/v1/tenants/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.POST, "/api/v1/tenants/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.PUT, "/api/v1/tenants/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/tenants/**").hasRole("ADMIN")
                    
                    // Tenant-level endpoints
                    .requestMatchers("/api/v1/products/**").hasAnyRole("ADMIN", "MANAGER", "STAFF")
                    .requestMatchers("/api/v1/orders/**").hasAnyRole("ADMIN", "MANAGER", "STAFF")
                    .requestMatchers("/api/v1/customers/**").hasAnyRole("ADMIN", "MANAGER", "STAFF")
                    
                    // KDS endpoints
                    .requestMatchers("/api/v1/kds/**").hasAnyRole("ADMIN", "MANAGER", "KITCHEN")
                    
                    // PDV endpoints
                    .requestMatchers("/api/v1/pos/**").hasAnyRole("ADMIN", "MANAGER", "CASHIER")
                    
                    // Default
                    .anyRequest().authenticated()
            }
            
        // Add tenant filter before authentication
        http.addFilterBefore(TenantFilter(tenantIdentifierResolver), UsernamePasswordAuthenticationFilter::class.java)
        
        return http.build()
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder {
        return BCryptPasswordEncoder(12)
    }
}
