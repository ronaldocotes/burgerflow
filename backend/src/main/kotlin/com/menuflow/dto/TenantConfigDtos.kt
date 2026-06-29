package com.menuflow.dto

import com.menuflow.model.TenantConfig
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
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
    // Programa de Fidelidade (Fase 3.3).
    val loyaltyEnabled: Boolean,
    val loyaltyPointsPerReal: Int,
    val loyaltyRewardThreshold: Int,
    val loyaltyRewardDescription: String?,
    // Campanhas WhatsApp + WAHA (Fase 3.4).
    val wahaPrimaryPhone: String?,
    val wahaFallbackPhone: String?,
    val campaignDailyLimit: Int,
    val campaignDelayMinSeconds: Int,
    val campaignDelayMaxSeconds: Int,
    // Recuperacao de carrinho abandonado (Fase 3.5).
    val cartRecoveryEnabled: Boolean,
    val cartRecoveryDelayMinutes: Int,
    val cartRecoveryMessage: String?,
    val cartRecoveryExpiryHours: Int,
    // Rastreamento de conversao (Fase 3.7). O token da Meta NUNCA e exposto:
    // a resposta traz apenas hasMetaToken (true se ha token salvo).
    val metaPixelId: String?,
    val hasMetaToken: Boolean,
    val metaTestEventCode: String?,
    val googleSgtmUrl: String?,
    val googleMeasurementId: String?,
    val conversionTrackingEnabled: Boolean,
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
                loyaltyEnabled            = c.loyaltyEnabled,
                loyaltyPointsPerReal      = c.loyaltyPointsPerReal,
                loyaltyRewardThreshold    = c.loyaltyRewardThreshold,
                loyaltyRewardDescription  = c.loyaltyRewardDescription,
                wahaPrimaryPhone          = c.wahaPrimaryPhone,
                wahaFallbackPhone         = c.wahaFallbackPhone,
                campaignDailyLimit        = c.campaignDailyLimit,
                campaignDelayMinSeconds   = c.campaignDelayMinSeconds,
                campaignDelayMaxSeconds   = c.campaignDelayMaxSeconds,
                cartRecoveryEnabled       = c.cartRecoveryEnabled,
                cartRecoveryDelayMinutes  = c.cartRecoveryDelayMinutes,
                cartRecoveryMessage       = c.cartRecoveryMessage,
                cartRecoveryExpiryHours   = c.cartRecoveryExpiryHours,
                metaPixelId               = c.metaPixelId,
                hasMetaToken              = !c.metaAccessToken.isNullOrBlank(),
                metaTestEventCode         = c.metaTestEventCode,
                googleSgtmUrl             = c.googleSgtmUrl,
                googleMeasurementId       = c.googleMeasurementId,
                conversionTrackingEnabled = c.conversionTrackingEnabled,
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
    // Fidelidade (Fase 3.3): omitido (null) = preservar valor atual.
    val loyaltyEnabled: Boolean? = null,
    @field:Min(0) @field:Max(1000)
    val loyaltyPointsPerReal: Int? = null,
    @field:Min(1) @field:Max(100000)
    val loyaltyRewardThreshold: Int? = null,
    @field:Size(max = 200)
    val loyaltyRewardDescription: String? = null,
    // Campanhas WhatsApp + WAHA (Fase 3.4): omitido (null) = preservar valor atual.
    @field:Size(max = 20)
    val wahaPrimaryPhone: String? = null,
    @field:Size(max = 20)
    val wahaFallbackPhone: String? = null,
    @field:Min(0) @field:Max(10000)
    val campaignDailyLimit: Int? = null,
    @field:Min(0) @field:Max(3600)
    val campaignDelayMinSeconds: Int? = null,
    @field:Min(0) @field:Max(3600)
    val campaignDelayMaxSeconds: Int? = null,
    // Recuperacao de carrinho abandonado (Fase 3.5): omitido (null) = preservar valor atual.
    val cartRecoveryEnabled: Boolean? = null,
    @field:Min(1) @field:Max(1440)
    val cartRecoveryDelayMinutes: Int? = null,
    @field:Size(max = 1000)
    val cartRecoveryMessage: String? = null,
    @field:Min(1) @field:Max(168)
    val cartRecoveryExpiryHours: Int? = null,
    // Rastreamento de conversao (Fase 3.7): omitido (null) = preservar valor atual.
    // metaAccessToken e aceito aqui (gravado), mas NUNCA devolvido no GET (so hasMetaToken).
    @field:Size(max = 100)
    val metaPixelId: String? = null,
    @field:Size(max = 500)
    val metaAccessToken: String? = null,
    @field:Size(max = 50)
    val metaTestEventCode: String? = null,
    @field:Size(max = 500)
    val googleSgtmUrl: String? = null,
    @field:Size(max = 50)
    val googleMeasurementId: String? = null,
    val conversionTrackingEnabled: Boolean? = null,
)
