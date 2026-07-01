package com.menuflow.platform

import com.menuflow.exception.BusinessException
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.repository.control.TenantRepository
import com.menuflow.security.SecurityUtils
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Fonte da verdade do entitlement de módulo, com cache local TTL 60s por
 * (tenantId, moduleKey). Lógica: se existe override na tabela tenant_module, usa o
 * valor dele; senão, usa o default do plano do tenant (ModuleKey.defaultEnabledFor).
 *
 * NOTA DE IMPLEMENTAÇÃO (desvio consciente do plano): o plano pedia Caffeine, mas a
 * dependência não está no classpath e não resolve offline neste ambiente. Um cache
 * TTL manual (ConcurrentHashMap + carimbo de expiração) cobre exatamente o requisito
 * — staleness de até 60s no toggle é aceitável — sem introduzir dependência nova.
 * Trocar por Caffeine depois é mecânico se o time preferir.
 *
 * Staleness de 60s é intencional: um toggle recém-feito pode levar até 1 min para
 * valer nos jobs/handlers. Para efeito IMEDIATO o mutador chama invalidate(tenantId).
 */
@Service
class ModuleGateService(
    private val tenantRepository: TenantRepository,
    private val moduleRepository: TenantModuleRepository,
    private val auditService: PlatformAuditService,
) {

    private data class Entry(val enabled: Boolean, val expiresAtMillis: Long)

    private val cache = ConcurrentHashMap<Pair<UUID, ModuleKey>, Entry>()

    private val ttlMillis = 60_000L

    /** Está o módulo habilitado para este tenant? (cacheado 60s). Fail-closed. */
    @Transactional("controlTransactionManager", readOnly = true)
    fun isEnabled(tenantId: UUID, moduleKey: ModuleKey): Boolean {
        val key = tenantId to moduleKey
        val now = System.currentTimeMillis()
        cache[key]?.let { if (it.expiresAtMillis > now) return it.enabled }
        val enabled = resolveFromDb(tenantId, moduleKey)
        cache[key] = Entry(enabled, now + ttlMillis)
        return enabled
    }

    /**
     * Status efetivo de TODOS os módulos para um tenant (por slug), com a origem
     * (override vs default do plano). Não usa o cache: é a visão administrativa
     * completa, lida fresca do banco.
     */
    @Transactional("controlTransactionManager", readOnly = true)
    fun statusFor(slug: String): List<ModuleStatusResponse> {
        val tenant = tenantRepository.findBySlug(slug)
            ?: throw ResourceNotFoundException("Tenant não encontrado: $slug")
        val overrides = moduleRepository.findByTenantId(tenant.id!!).associateBy { it.moduleKey }
        return ModuleKey.entries.map { mk ->
            val override = overrides[mk.name]
            val enabled = override?.enabled ?: mk.defaultEnabledFor(tenant.subscriptionPlan)
            val source = if (override != null) {
                ModuleStatusResponse.Source.OVERRIDE
            } else {
                ModuleStatusResponse.Source.PLAN_DEFAULT
            }
            ModuleStatusResponse(
                moduleKey = mk.name,
                label = mk.label,
                enabled = enabled,
                source = source.name,
            )
        }
    }

    /**
     * Liga/desliga um módulo para um tenant (upsert do override em tenant_module),
     * invalida o cache e audita. Toggle otimista: a UI aplica na hora e faz rollback
     * visível se a chamada falhar. Devolve o status efetivo resultante.
     */
    @Transactional("controlTransactionManager")
    fun toggle(slug: String, moduleKeyRaw: String, enabled: Boolean): ModuleStatusResponse {
        val tenant = tenantRepository.findBySlug(slug)
            ?: throw ResourceNotFoundException("Tenant não encontrado: $slug")
        val moduleKey = try {
            ModuleKey.valueOf(moduleKeyRaw.trim().uppercase())
        } catch (e: IllegalArgumentException) {
            throw BusinessException("Módulo inválido: $moduleKeyRaw")
        }
        val principal = SecurityUtils.currentPrincipalOrThrow()

        val existing = moduleRepository.findByTenantIdAndModuleKey(tenant.id!!, moduleKey.name)
        val before = existing?.enabled
        val row = existing?.apply {
            this.enabled = enabled
            this.updatedByUserId = principal.userId
            this.updatedAt = Instant.now()
        } ?: TenantModule(
            tenantId = tenant.id!!,
            moduleKey = moduleKey.name,
            enabled = enabled,
            updatedByUserId = principal.userId,
        )
        moduleRepository.save(row)

        invalidate(tenant.id!!)

        auditService.record(
            action = "MODULE_TOGGLE",
            targetTenantId = tenant.id,
            targetEntity = "tenant_module:${moduleKey.name}",
            before = before?.let { mapOf("enabled" to it) },
            after = mapOf("enabled" to enabled),
        )

        return ModuleStatusResponse(
            moduleKey = moduleKey.name,
            label = moduleKey.label,
            enabled = enabled,
            source = ModuleStatusResponse.Source.OVERRIDE.name,
        )
    }

    /** Invalida o cache de um tenant inteiro (após toggle ou troca de plano). */
    fun invalidate(tenantId: UUID) {
        cache.keys.removeIf { it.first == tenantId }
    }

    private fun resolveFromDb(tenantId: UUID, moduleKey: ModuleKey): Boolean {
        val override = moduleRepository.findByTenantIdAndModuleKey(tenantId, moduleKey.name)
        if (override != null) return override.enabled
        val tenant = tenantRepository.findById(tenantId).orElse(null) ?: return false
        return moduleKey.defaultEnabledFor(tenant.subscriptionPlan)
    }
}
