package com.menuflow.config

import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.cache.CacheManager
import org.springframework.cache.caffeine.CaffeineCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.TimeUnit

/**
 * Configuracao de cache em memoria com Caffeine (Fase 3.4).
 *
 * Caches gerenciados:
 *  - rfv-scores: calculo RFV de todos os clientes do tenant (query pesada).
 *    TTL 10 min. Chave = slug do tenant (SpEL em @Cacheable).
 *
 * Por que CaffeineCache e nao simple?
 *  O tipo 'simple' nao tem TTL nem tamanho maximo. Caffeine resolve os dois.
 */
@Configuration
class CacheConfig {

    @Bean
    fun cacheManager(): CacheManager {
        val manager = CaffeineCacheManager("rfv-scores")
        manager.setCaffeine(
            Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .maximumSize(2000),
        )
        return manager
    }
}
