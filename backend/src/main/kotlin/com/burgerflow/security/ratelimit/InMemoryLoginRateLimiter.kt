package com.burgerflow.security.ratelimit

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Node-local login rate limiter backed by Bucket4j (Sprint 2). One bucket per IP,
 * cached in a ConcurrentHashMap. Used in dev/test and as the fallback when no
 * Redis is configured. In a multi-instance deployment prefer the Redis-backed
 * limiter so the counter is shared (see RedisLoginRateLimiter / config).
 */
class InMemoryLoginRateLimiter(
    private val props: RateLimitProperties,
) : LoginRateLimiter {

    private val log = LoggerFactory.getLogger(javaClass)
    private val buckets = ConcurrentHashMap<String, Bucket>()

    private fun newBucket(): Bucket {
        val limit = Bandwidth.builder()
            .capacity(props.capacity)
            .refillGreedy(props.capacity, Duration.ofSeconds(props.refillPeriodSeconds))
            .build()
        return Bucket.builder().addLimit(limit).build()
    }

    override fun tryConsume(key: String): LoginRateLimiter.Decision {
        val bucket = buckets.computeIfAbsent(key) { newBucket() }
        val probe = bucket.tryConsumeAndReturnRemaining(1)
        if (probe.isConsumed) {
            return LoginRateLimiter.Decision(allowed = true, retryAfterSeconds = 0)
        }
        val retryAfter = max(1L, Duration.ofNanos(probe.nanosToWaitForRefill).seconds)
        log.debug("Login rate limit hit for key {} (retry after {}s)", key, retryAfter)
        return LoginRateLimiter.Decision(allowed = false, retryAfterSeconds = retryAfter)
    }
}
