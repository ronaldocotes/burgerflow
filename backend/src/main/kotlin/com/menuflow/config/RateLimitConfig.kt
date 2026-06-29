package com.menuflow.config

import com.menuflow.security.ratelimit.AiTenantRateLimiter
import com.menuflow.security.ratelimit.InMemoryAiTenantRateLimiter
import com.menuflow.security.ratelimit.InMemoryLoginRateLimiter
import com.menuflow.security.ratelimit.LoginRateLimiter
import com.menuflow.security.ratelimit.PublicOrderRateLimitProperties
import com.menuflow.security.ratelimit.RateLimitProperties
import com.menuflow.security.ratelimit.InMemoryWebhookMessageDeduplicator
import com.menuflow.security.ratelimit.RedisAiTenantRateLimiter
import com.menuflow.security.ratelimit.RedisLoginRateLimiter
import com.menuflow.security.ratelimit.RedisWebhookMessageDeduplicator
import com.menuflow.security.ratelimit.WebhookMessageDeduplicator
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.core.StringRedisTemplate
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
 *  - `menuflow.rate-limit.login.backend=redis` -> Redis-backed (shared across
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
        @org.springframework.beans.factory.annotation.Value("\${menuflow.rate-limit.login.backend:memory}")
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

    /**
     * Limiter dedicado para o endpoint publico de pedidos (capacidade propria, 20/min).
     * Reusa a mesma abstracao keyed-by-IP do login; backend memory/redis configuravel.
     */
    @Bean
    fun publicOrderRateLimiter(
        publicOrderProps: PublicOrderRateLimitProperties,
        redisProps: RedisProperties,
    ): LoginRateLimiter {
        val props = RateLimitProperties(
            enabled = publicOrderProps.enabled,
            capacity = publicOrderProps.capacity,
            refillPeriodSeconds = publicOrderProps.refillPeriodSeconds,
        )
        if (publicOrderProps.backend.equals("redis", ignoreCase = true)) {
            return runCatching { redisLimiter(props, redisProps) }
                .getOrElse {
                    log.warn("Redis public-order limiter unavailable ({}), falling back to in-memory", it.message)
                    InMemoryLoginRateLimiter(props)
                }
        }
        log.info("Public-order rate limiter: in-memory ({}/{}s per IP)", props.capacity, props.refillPeriodSeconds)
        return InMemoryLoginRateLimiter(props)
    }

    /**
     * Rate limiter do Copiloto de IA por TENANT (Fase 4.2). Distinto do Bucket4j (que e
     * por usuario): impede que um restaurante consuma toda a capacidade do LiteLLM.
     *  - backend=redis -> contador compartilhado (INCR+EXPIRE) via StringRedisTemplate;
     *  - qualquer outro (default) -> contador em memoria (dev/test, zero dependencia).
     * StringRedisTemplate vem por ObjectProvider para nao forcar a criacao do bean (e
     * do Redis) quando o backend e memory.
     */
    @Bean
    fun aiTenantRateLimiter(
        stringRedisTemplate: ObjectProvider<StringRedisTemplate>,
        @org.springframework.beans.factory.annotation.Value("\${menuflow.ai.rate-limit.backend:memory}")
        backend: String,
        @org.springframework.beans.factory.annotation.Value("\${menuflow.ai.rate-limit.tenant-limit:20}")
        limit: Int,
        @org.springframework.beans.factory.annotation.Value("\${menuflow.ai.rate-limit.window-seconds:60}")
        windowSeconds: Long,
    ): AiTenantRateLimiter {
        if (backend.equals("redis", ignoreCase = true)) {
            val redis = stringRedisTemplate.ifAvailable
            if (redis != null) {
                log.info("AI tenant rate limiter: redis ({}/{}s per tenant)", limit, windowSeconds)
                return RedisAiTenantRateLimiter(redis, limit, windowSeconds)
            }
            log.warn("AI tenant rate limiter: StringRedisTemplate ausente, usando in-memory")
        }
        log.info("AI tenant rate limiter: in-memory ({}/{}s per tenant)", limit, windowSeconds)
        return InMemoryAiTenantRateLimiter(limit, windowSeconds)
    }

    /**
     * Deduplicador de mensagens de webhook do bot (Fase 4.3). Idempotencia por
     * messageId (WAHA reentrega ate 2xx). backend=redis -> SET NX EX compartilhado;
     * qualquer outro (default) -> em memoria (dev/test, zero dependencia). Mesmo
     * padrao do aiTenantRateLimiter (StringRedisTemplate via ObjectProvider).
     */
    @Bean
    fun webhookMessageDeduplicator(
        stringRedisTemplate: ObjectProvider<StringRedisTemplate>,
        @org.springframework.beans.factory.annotation.Value("\${menuflow.bot.dedup.backend:memory}")
        backend: String,
    ): WebhookMessageDeduplicator {
        if (backend.equals("redis", ignoreCase = true)) {
            val redis = stringRedisTemplate.ifAvailable
            if (redis != null) {
                log.info("Bot webhook deduplicator: redis (SET NX EX 24h)")
                return RedisWebhookMessageDeduplicator(redis)
            }
            log.warn("Bot webhook deduplicator: StringRedisTemplate ausente, usando in-memory")
        }
        log.info("Bot webhook deduplicator: in-memory")
        return InMemoryWebhookMessageDeduplicator()
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
