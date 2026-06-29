package com.menuflow.security.ratelimit

import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Rate limit do Copiloto de IA por TENANT (Fase 4.2). Distinto do rate limit por
 * usuario (Bucket4j): evita que UM restaurante consuma toda a capacidade do gateway
 * LiteLLM. Janela fixa: ate [limit] requisicoes por [windowSeconds] por tenant.
 *
 * Duas implementacoes (selecionadas em RateLimitConfig.aiTenantRateLimiter):
 *  - [InMemoryAiTenantRateLimiter]: node-local, default (dev/test, zero dependencia);
 *  - [RedisAiTenantRateLimiter]: contador compartilhado entre instancias (producao),
 *    via INCR + EXPIRE simples (sem Bucket4j).
 */
interface AiTenantRateLimiter {
    /** true se a requisicao e permitida; false se o tenant estourou a cota da janela. */
    fun tryAcquire(tenantSlug: String): Boolean
}

/**
 * Contador em memoria por (tenant, janela). Janela fixa = epoch/windowMillis: ao virar
 * a janela o contador zera. Suficiente para uma instancia; producao usa Redis para
 * compartilhar entre nos.
 */
class InMemoryAiTenantRateLimiter(
    private val limit: Int,
    private val windowSeconds: Long,
) : AiTenantRateLimiter {

    private data class Window(val index: Long, val count: Int)

    private val counters = ConcurrentHashMap<String, Window>()

    override fun tryAcquire(tenantSlug: String): Boolean {
        val windowMillis = windowSeconds * 1000
        val idx = System.currentTimeMillis() / windowMillis
        val updated = counters.compute(tenantSlug) { _, prev ->
            if (prev == null || prev.index != idx) Window(idx, 1) else Window(idx, prev.count + 1)
        }!!
        return updated.count <= limit
    }
}

/**
 * Contador no Redis (compartilhado entre instancias). Chave `ai:rl:tenant:{slug}`:
 * INCR atomico; no primeiro hit da janela define o EXPIRE de [windowSeconds]. Se o
 * Redis estiver indisponivel, falha ABERTO (permite) — o rate limit nunca derruba o
 * copiloto; a protecao real e best-effort.
 */
class RedisAiTenantRateLimiter(
    private val redis: StringRedisTemplate,
    private val limit: Int,
    private val windowSeconds: Long,
) : AiTenantRateLimiter {

    override fun tryAcquire(tenantSlug: String): Boolean = try {
        val key = "ai:rl:tenant:$tenantSlug"
        val count = redis.opsForValue().increment(key) ?: return true
        if (count == 1L) redis.expire(key, Duration.ofSeconds(windowSeconds))
        count <= limit
    } catch (e: Exception) {
        true // fail-open: indisponibilidade do Redis nao bloqueia o copiloto
    }
}
