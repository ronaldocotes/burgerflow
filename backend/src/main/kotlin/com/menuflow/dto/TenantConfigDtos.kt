package com.menuflow.dto

import com.menuflow.model.TenantConfig
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Size
import java.math.BigDecimal

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
    // Alíquotas do DRE (Fase 3.1).
    val marketplaceFeePct: BigDecimal,
    val cardFeePct: BigDecimal,
    val taxPct: BigDecimal,
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
                marketplaceFeePct = c.marketplaceFeePct,
                cardFeePct        = c.cardFeePct,
                taxPct            = c.taxPct,
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
    // Alíquotas do DRE (Fase 3.1): 0..100 (%). Omitido (null) = preservar valor atual.
    @field:DecimalMin("0.0") @field:DecimalMax("100.0")
    val marketplaceFeePct: BigDecimal? = null,
    @field:DecimalMin("0.0") @field:DecimalMax("100.0")
    val cardFeePct: BigDecimal? = null,
    @field:DecimalMin("0.0") @field:DecimalMax("100.0")
    val taxPct: BigDecimal? = null,
)
