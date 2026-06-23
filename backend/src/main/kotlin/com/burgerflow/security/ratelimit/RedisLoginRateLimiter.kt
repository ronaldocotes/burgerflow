package com.burgerflow.security.ratelimit

import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.distributed.proxy.ProxyManager
import org.slf4j.LoggerFactory
import java.time.Duration
import kotlin.math.max

/**
 * Redis-backed login rate limiter (Sprint 2 production path). Counters are stored
 * in Redis via a Bucket4j [ProxyManager], so the 5/min limit is enforced across
 * ALL application instances behind the load balancer (an attacker cannot dodge it
 * by hitting a different node).
 *
 * The [proxyManager] is wired in RateLimitConfig only when Redis is available and
 * the backend is selected; otherwise the in-memory limiter is used instead.
 */
class RedisLoginRateLimiter(
    private val proxyManager: ProxyManager<String>,
    private val props: RateLimitProperties,
) : LoginRateLimiter {

    private val log = LoggerFactory.getLogger(javaClass)

    private val configuration: BucketConfiguration =
        BucketConfiguration.builder()
            .addLimit { limit ->
                limit.capacity(props.capacity)
                    .refillGreedy(props.capacity, Duration.ofSeconds(props.refillPeriodSeconds))
            }
            .build()

    override fun tryConsume(key: String): LoginRateLimiter.Decision {
        val bucket = proxyManager.builder().build(key) { configuration }
        val probe = bucket.tryConsumeAndReturnRemaining(1)
        if (probe.isConsumed) {
            return LoginRateLimiter.Decision(allowed = true, retryAfterSeconds = 0)
        }
        val retryAfter = max(1L, Duration.ofNanos(probe.nanosToWaitForRefill).seconds)
        log.debug("Login rate limit hit (redis) for key {} (retry after {}s)", key, retryAfter)
        return LoginRateLimiter.Decision(allowed = false, retryAfterSeconds = retryAfter)
    }
}
