package com.menuflow.security.ratelimit

import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Idempotencia de mensagens de webhook (Fase 4.3 — bot WhatsApp). O WAHA reentrega a
 * mesma mensagem se nao receber 2xx rapido; processar duas vezes faria o bot responder
 * em dobro. Marcamos o messageId por 24h: a primeira chamada vence (processa), as
 * repeticoes sao ignoradas.
 *
 * Duas implementacoes (selecionadas em RateLimitConfig.webhookMessageDeduplicator),
 * mesmo padrao do [AiTenantRateLimiter]:
 *  - [InMemoryWebhookMessageDeduplicator]: node-local, default (dev/test, zero dep);
 *  - [RedisWebhookMessageDeduplicator]: compartilhado entre instancias (producao),
 *    via SET NX EX (atomico).
 */
interface WebhookMessageDeduplicator {
    /**
     * Marca [key] como processada. Retorna true se era NOVA (deve processar) e false
     * se ja havia sido vista (ignorar — reentrega/duplicata).
     */
    fun markIfNew(key: String): Boolean
}

/**
 * Dedup em memoria. Guarda o instante de cada chave e descarta as expiradas
 * preguicosamente quando o mapa cresce. Suficiente para uma instancia; producao usa
 * Redis para compartilhar entre nos.
 */
class InMemoryWebhookMessageDeduplicator(
    private val ttlSeconds: Long = 86_400,
) : WebhookMessageDeduplicator {

    private val seen = ConcurrentHashMap<String, Long>()

    override fun markIfNew(key: String): Boolean {
        val now = System.currentTimeMillis()
        // Limpeza preguicosa para o mapa nao crescer sem limite num processo longo.
        if (seen.size > MAX_ENTRIES) {
            val cutoff = now - ttlSeconds * 1000
            seen.entries.removeIf { it.value < cutoff }
        }
        // putIfAbsent e atomico: retorna null se a chave era nova.
        return seen.putIfAbsent(key, now) == null
    }

    companion object {
        private const val MAX_ENTRIES = 100_000
    }
}

/**
 * Dedup no Redis (compartilhado entre instancias). `SET key 1 NX EX ttl`: retorna true
 * so quando a chave NAO existia. Se o Redis cair, falha ABERTO (trata como nova) — o
 * bot nunca trava por indisponibilidade do Redis; o pior caso e uma resposta duplicada.
 */
class RedisWebhookMessageDeduplicator(
    private val redis: StringRedisTemplate,
    private val ttlSeconds: Long = 86_400,
) : WebhookMessageDeduplicator {

    override fun markIfNew(key: String): Boolean = try {
        redis.opsForValue().setIfAbsent("bot:dedup:$key", "1", Duration.ofSeconds(ttlSeconds)) ?: true
    } catch (e: Exception) {
        true // fail-open: indisponibilidade do Redis nao bloqueia o bot
    }
}
