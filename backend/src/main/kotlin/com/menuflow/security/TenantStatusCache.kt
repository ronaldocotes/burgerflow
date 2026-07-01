package com.menuflow.security

import com.menuflow.repository.control.TenantRepository
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache do estado ativo/inativo de cada tenant, com TTL de 60s, para o JwtAuthFilter
 * poder BLOQUEAR um tenant desativado sem bater no banco a cada request.
 *
 * Por quê: login e refresh já recusam tenant inativo (AuthService), mas o access
 * token é stateless e curto — sem esta checagem, uma sessão viva sobreviveria até o
 * token expirar mesmo após a desativação. Com o cache, a desativação passa a valer
 * em no máximo 60s para requests autenticados.
 *
 * Fail-closed: tenant desconhecido (sem linha) => inativo. Consulta o banco de
 * CONTROLE (TenantRepository), independente do TenantContext.
 *
 * NOTA: cache TTL manual (ConcurrentHashMap) em vez de Caffeine — a dependência não
 * está no classpath e não resolve offline; o requisito (não bater no banco a cada
 * request, staleness <=60s) é atendido igual.
 */
@Component
class TenantStatusCache(
    private val tenantRepository: TenantRepository,
) {

    private data class Entry(val active: Boolean, val expiresAtMillis: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    private val ttlMillis = 60_000L

    fun isActive(slug: String): Boolean {
        val now = System.currentTimeMillis()
        cache[slug]?.let { if (it.expiresAtMillis > now) return it.active }
        val active = tenantRepository.findBySlug(slug)?.isActive ?: false
        cache[slug] = Entry(active, now + ttlMillis)
        return active
    }

    /** Descarta a entrada de um tenant (ex.: após ativar/desativar no painel). */
    fun invalidate(slug: String) {
        cache.remove(slug)
    }
}
