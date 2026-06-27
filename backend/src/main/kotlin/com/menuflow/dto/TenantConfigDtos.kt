package com.menuflow.dto

import com.menuflow.model.TenantConfig

/**
 * Estado das configurações do tenant. Exposto em GET /config e devolvido por
 * PATCH /config. Campos futuros (novos toggles) entram aqui de forma aditiva.
 */
data class TenantConfigResponse(
    val autoAcceptOrders: Boolean,
) {
    companion object {
        fun from(c: TenantConfig) = TenantConfigResponse(autoAcceptOrders = c.autoAcceptOrders)
    }
}

/**
 * Atualização parcial das configurações (PATCH). Hoje só o aceite automático.
 * Boolean não-nulo: ausência do campo no corpo vira 400 (Jackson/Kotlin).
 */
data class TenantConfigUpdateRequest(
    val autoAcceptOrders: Boolean,
)
