package com.menuflow.security.ratelimit

import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Rate limit das ESCRITAS de anuncio por TENANT (Fase 8.2): criar/ativar campanha. Estas
 * rotas gastam dinheiro real e disparam a saga externa na Meta — poucas por minuto bastam.
 * Distinto do rate limit por usuario (login) e do copiloto de IA. Mesmo padrao do
 * [AiTenantRateLimiter] (in-memory default; Redis compartilhado em producao).
 */
interface AdsWriteRateLimiter {
    /** true se a requisicao e permitida; false se o tenant estourou a cota da janela. */
    fun tryAcquire(tenantSlug: String): Boolean
}

/** Contador em memoria por (tenant, janela fixa). Suficiente para uma instancia. */
class InMemoryAdsWriteRateLimiter(
    private val limit: Int,
    private val windowSeconds: Long,
) : AdsWriteRateLimiter {

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
 * Contador no Redis (compartilhado entre instancias). Chave `ads:rl:write:{slug}`: INCR
 * atomico + EXPIRE na primeira contagem da janela.
 *
 * IMPORTANTE (decisao consciente, difere do copiloto): aqui o Redis indisponivel falha
 * FECHADO (nega). O copiloto pode falhar aberto porque so custa tokens de LLM; a escrita de
 * anuncio GASTA DINHEIRO REAL — na duvida, e mais seguro barrar e o operador tentar de novo.
 */
class RedisAdsWriteRateLimiter(
    private val redis: StringRedisTemplate,
    private val limit: Int,
    private val windowSeconds: Long,
) : AdsWriteRateLimiter {

    override fun tryAcquire(tenantSlug: String): Boolean = try {
        val key = "ads:rl:write:$tenantSlug"
        val count = redis.opsForValue().increment(key) ?: return false
        if (count == 1L) redis.expire(key, Duration.ofSeconds(windowSeconds))
        count <= limit
    } catch (e: Exception) {
        false // fail-closed: escrita que gasta verba nao passa se nao pudermos contar
    }
}
