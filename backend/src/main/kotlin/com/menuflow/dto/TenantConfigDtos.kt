package com.menuflow.dto

import com.menuflow.model.TenantConfig
import jakarta.validation.constraints.Size

/**
 * Estado das configurações do tenant. Exposto em GET /config e devolvido por
 * PATCH /config. Campos futuros (novos toggles) entram aqui de forma aditiva.
 */
data class TenantConfigResponse(
    val autoAcceptOrders: Boolean,
    val pixKey: String?,
    val restaurantName: String?,
    val logoUrl: String?,
    val coverUrl: String?,
    val address: String?,
    val openingHours: String?,
    val merchantCity: String?,
) {
    companion object {
        fun from(c: TenantConfig) =
            TenantConfigResponse(
                autoAcceptOrders = c.autoAcceptOrders,
                pixKey           = c.pixKey,
                restaurantName   = c.restaurantName,
                logoUrl          = c.logoUrl,
                coverUrl         = c.coverUrl,
                address          = c.address,
                openingHours     = c.openingHours,
                merchantCity     = c.merchantCity,
            )
    }
}

/**
 * Atualização parcial das configurações (PATCH).
 * Semantica: campo omitido (null) = preservar valor atual; campo enviado = sobrescrever.
 * autoAcceptOrders nao-nulo garante que ausencia vira 400 (Jackson/Kotlin).
 */
data class TenantConfigUpdateRequest(
    val autoAcceptOrders: Boolean,
    @field:Size(max = 140)
    val pixKey: String? = null,
    @field:Size(max = 100)
    val restaurantName: String? = null,
    @field:Size(max = 500)
    val logoUrl: String? = null,
    @field:Size(max = 500)
    val coverUrl: String? = null,
    @field:Size(max = 200)
    val address: String? = null,
    @field:Size(max = 200)
    val openingHours: String? = null,
    @field:Size(max = 50)
    val merchantCity: String? = null,
)
