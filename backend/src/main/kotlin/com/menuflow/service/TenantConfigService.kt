package com.menuflow.service

import com.menuflow.dto.TenantConfigResponse
import com.menuflow.dto.TenantConfigUpdateRequest
import com.menuflow.model.TenantConfig
import com.menuflow.repository.tenant.TenantConfigRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Configurações operacionais do tenant. A linha vive no banco do próprio tenant
 * (rota pela TenantContext do token assinado), então não há escopo cross-tenant
 * a checar aqui — cada conexão já aterrissa no banco certo.
 */
@Service
class TenantConfigService(
    private val repository: TenantConfigRepository,
) {

    @Transactional("tenantTransactionManager", readOnly = true)
    fun get(): TenantConfigResponse =
        TenantConfigResponse.from(repository.findFirstByOrderByCreatedAtAsc() ?: TenantConfig())

    /**
     * Atualiza (upsert) a configuração do tenant. A migração V13 já semeia uma
     * linha em cada banco, mas fazemos getOrCreate por robustez (tenant antigo
     * sem linha, ou linha removida).
     */
    @Transactional("tenantTransactionManager")
    fun update(req: TenantConfigUpdateRequest): TenantConfigResponse {
        val config = repository.findFirstByOrderByCreatedAtAsc() ?: TenantConfig()
        config.autoAcceptOrders = req.autoAcceptOrders
        req.pixKey?.let          { config.pixKey         = it.trim().ifBlank { null } }
        req.restaurantName?.let  { config.restaurantName = it.trim().ifBlank { null } }
        req.logoUrl?.let         { config.logoUrl        = it.trim().ifBlank { null } }
        req.coverUrl?.let        { config.coverUrl       = it.trim().ifBlank { null } }
        req.address?.let         { config.address        = it.trim().ifBlank { null } }
        req.openingHours?.let    { config.openingHours   = it.trim().ifBlank { null } }
        req.merchantCity?.let    { config.merchantCity   = it.trim().ifBlank { null } }
        return TenantConfigResponse.from(repository.save(config))
    }
}
