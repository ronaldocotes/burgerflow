package com.burgerflow

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Login rate limit (Sprint 2): max 5 attempts / minute per IP. The 6th attempt
 * from the same IP returns 429 + Retry-After, BEFORE auth runs (so the username
 * need not even exist). A different IP is unaffected.
 *
 * Re-enables the limiter (the base disables it for the rest of the suite) and
 * uses unique X-Forwarded-For IPs per case so the node-local buckets do not
 * bleed across tests sharing this JVM.
 */
@AutoConfigureMockMvc
class LoginRateLimitTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
) : IntegrationTestBase() {

    companion object {
        // Overrides the base's enabled=false (later registration wins).
        @JvmStatic
        @DynamicPropertySource
        fun enableRateLimit(registry: DynamicPropertyRegistry) {
            registry.add("burgerflow.rate-limit.login.enabled") { "true" }
            registry.add("burgerflow.rate-limit.login.backend") { "memory" }
            registry.add("burgerflow.rate-limit.login.capacity") { "5" }
            registry.add("burgerflow.rate-limit.login.refill-period-seconds") { "60" }
        }
    }

    private fun loginBody() = objectMapper.writeValueAsString(
        mapOf("email" to "ghost@nowhere.com", "password" to "whatever", "tenantSlug" to "nope"),
    )

    @Test
    fun `sixth login attempt from same IP is throttled with 429 and Retry-After`() {
        val ip = "203.0.113.7"
        // 5 attempts allowed (each is a normal 401 - invalid credentials).
        repeat(5) {
            mockMvc.perform(
                post("/auth/login").header("X-Forwarded-For", ip)
                    .contentType(MediaType.APPLICATION_JSON).content(loginBody()),
            ).andExpect(status().isUnauthorized)
        }
        // 6th is rate-limited before auth.
        val res = mockMvc.perform(
            post("/auth/login").header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON).content(loginBody()),
        )
            .andExpect(status().isTooManyRequests)
            .andExpect(header().exists("Retry-After"))
            .andReturn()
        assertNotNull(res.response.getHeader("Retry-After"))
    }

    @Test
    fun `a different IP is not affected by another IP's limit`() {
        val busy = "203.0.113.8"
        repeat(6) {
            mockMvc.perform(
                post("/auth/login").header("X-Forwarded-For", busy)
                    .contentType(MediaType.APPLICATION_JSON).content(loginBody()),
            )
        }
        // Fresh IP still gets a normal 401, not 429.
        mockMvc.perform(
            post("/auth/login").header("X-Forwarded-For", "203.0.113.9")
                .contentType(MediaType.APPLICATION_JSON).content(loginBody()),
        ).andExpect(status().isUnauthorized)
    }
}
