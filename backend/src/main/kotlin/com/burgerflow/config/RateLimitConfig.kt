package com.burgerflow.config

import com.burgerflow.security.ratelimit.InMemoryLoginRateLimiter
import com.burgerflow.security.ratelimit.LoginRateLimiter
import com.burgerflow.security.ratelimit.RateLimitProperties
import com.burgerflow.security.ratelimit.RedisLoginRateLimiter
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.codec.ByteArrayCodec
import io.lettuce.core.codec.RedisCodec
import io.lettuce.core.codec.StringCodec
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.data.redis.RedisProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * Selects the login rate limiter (Sprint 2):
 *  - `burgerflow.rate-limit.login.backend=redis` -> Redis-backed (shared across
 *    instances). Requires Spring Redis properties (host/port).
 *  - anything else (default) -> node-local in-memory limiter.
 *
 * The Redis path is opt-in so dev/test stay zero-dependency; production turns it
 * on. Both enforce the same 5/min-per-IP limit.
 */
@Configuration
class RateLimitConfig {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun loginRateLimiter(
        props: RateLimitProperties,
        redisProps: RedisProperties,
        @org.springframework.beans.factory.annotation.Value("\${burgerflow.rate-limit.login.backend:memory}")
        backend: String,
    ): LoginRateLimiter {
        if (backend.equals("redis", ignoreCase = true)) {
            return runCatching { redisLimiter(props, redisProps) }
                .getOrElse {
                    log.warn("Redis login limiter unavailable ({}), falling back to in-memory", it.message)
                    InMemoryLoginRateLimiter(props)
                }
        }
        log.info("Login rate limiter: in-memory ({}/{}s per IP)", props.capacity, props.refillPeriodSeconds)
        return InMemoryLoginRateLimiter(props)
    }

    private fun redisLimiter(props: RateLimitProperties, redisProps: RedisProperties): LoginRateLimiter {
        val uri = RedisURI.Builder
            .redis(redisProps.host, redisProps.port)
            .apply { if (!redisProps.password.isNullOrBlank()) withPassword(redisProps.password!!.toCharArray()) }
            .build()
        val client = RedisClient.create(uri)
        val connection = client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE))
        val proxyManager = LettuceBasedProxyManager.builderFor(connection)
            .withExpirationStrategy(
                ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                    Duration.ofSeconds(props.refillPeriodSeconds * 2),
                ),
            )
            .build()
        log.info("Login rate limiter: redis ({}/{}s per IP)", props.capacity, props.refillPeriodSeconds)
        return RedisLoginRateLimiter(proxyManager, props)
    }
}
