package com.menuflow.dto

import com.menuflow.model.TenantConfig
import com.menuflow.util.ColorContrast
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import java.math.BigDecimal

/**
 * Dados de contraste (WCAG) da cor de marca (issue #12), calculados no servidor.
 * O frontend usa recommendedTextColor para pintar o texto sobre a cor principal
 * e meetsAA para exibir o selo de acessibilidade. Ver ColorContrast.
 */
data class ThemeContrastInfo(
    val primaryColor: String,
    val ratioOnWhite: Double,
    val ratioOnBlack: Double,
    val recommendedTextColor: String,
    val meetsAA: Boolean,
) {
    companion object {
        /** null quando nao ha cor configurada ou o hex e invalido. */
        fun of(primaryColor: String?): ThemeContrastInfo? {
            if (primaryColor.isNullOrBlank() || !ColorContrast.isValidHex(primaryColor)) return null
            val color = ColorContrast.normalize(primaryColor)
            val onWhite = ColorContrast.contrastRatio(color, ColorContrast.WHITE)
            val onBlack = ColorContrast.contrastRatio(color, ColorContrast.BLACK)
            val recommended = if (onWhite >= onBlack) ColorContrast.WHITE else ColorContrast.BLACK
            return ThemeContrastInfo(
                primaryColor = color,
                ratioOnWhite = round2(onWhite),
                ratioOnBlack = round2(onBlack),
                recommendedTextColor = recommended,
                meetsAA = maxOf(onWhite, onBlack) >= 4.5,
            )
        }

        private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
    }
}

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
    // Endereco estruturado + pin do mapa (issue #7).
    val postalCode: String?,
    val street: String?,
    val streetNumber: String?,
    val addressComplement: String?,
    val neighborhood: String?,
    val stateUf: String?,
    val restaurantLat: Double?,
    val restaurantLng: Double?,
    // Tempo estimado por modalidade, em minutos (issue #9).
    val deliveryTimeMinMinutes: Int,
    val deliveryTimeMaxMinutes: Int,
    val pickupTimeMinMinutes: Int,
    val pickupTimeMaxMinutes: Int,
    val dineinTimeMinMinutes: Int,
    val dineinTimeMaxMinutes: Int,
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
    /** true quando ha api_secret do GA4 salvo (o VALOR nunca e exposto — write-only). */
    val googleApiSecretConfigured: Boolean,
    val conversionTrackingEnabled: Boolean,
    // Copiloto do dono: IA (Fase 4.1).
    val aiEnabled: Boolean,
    val aiSystemPrompt: String?,
    val aiDailyLimit: Int,
    // Hardening do Copiloto (Fase 4.2).
    val aiMaxMessageLength: Int,
    val aiBlockedPatterns: String?,
    // Bot WhatsApp inbound (Fase 4.3).
    val botEnabled: Boolean,
    val botSystemPrompt: String?,
    val botHandoffKeyword: String?,
    val botWelcomeMessage: String?,
    val botHandoffMessage: String?,
    val openingHoursMonday: String?,
    val openingHoursTuesday: String?,
    val openingHoursWednesday: String?,
    val openingHoursThursday: String?,
    val openingHoursFriday: String?,
    val openingHoursSaturday: String?,
    val openingHoursSunday: String?,
    // Tema do cardapio publico (Fase CONFIG-B, issue #12).
    val themePrimaryColor: String?,
    val themeShowPrices: Boolean,
    val themeShowDescriptions: Boolean,
    val themeShowPhotos: Boolean,
    /** Contraste WCAG da cor de marca (null quando sem cor configurada). */
    val themeContrast: ThemeContrastInfo?,
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
                postalCode        = c.postalCode,
                street            = c.street,
                streetNumber      = c.streetNumber,
                addressComplement = c.addressComplement,
                neighborhood      = c.neighborhood,
                stateUf           = c.stateUf,
                restaurantLat     = c.restaurantLat,
                restaurantLng     = c.restaurantLng,
                deliveryTimeMinMinutes = c.deliveryTimeMinMinutes,
                deliveryTimeMaxMinutes = c.deliveryTimeMaxMinutes,
                pickupTimeMinMinutes   = c.pickupTimeMinMinutes,
                pickupTimeMaxMinutes   = c.pickupTimeMaxMinutes,
                dineinTimeMinMinutes   = c.dineinTimeMinMinutes,
                dineinTimeMaxMinutes   = c.dineinTimeMaxMinutes,
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
                googleApiSecretConfigured = c.googleApiSecretEnc != null && c.googleApiSecretIv != null,
                conversionTrackingEnabled = c.conversionTrackingEnabled,
                aiEnabled                 = c.aiEnabled,
                aiSystemPrompt            = c.aiSystemPrompt,
                aiDailyLimit              = c.aiDailyLimit,
                aiMaxMessageLength        = c.aiMaxMessageLength,
                aiBlockedPatterns         = c.aiBlockedPatterns,
                botEnabled                = c.botEnabled,
                botSystemPrompt           = c.botSystemPrompt,
                botHandoffKeyword         = c.botHandoffKeyword,
                botWelcomeMessage         = c.botWelcomeMessage,
                botHandoffMessage         = c.botHandoffMessage,
                openingHoursMonday        = c.openingHoursMonday,
                openingHoursTuesday       = c.openingHoursTuesday,
                openingHoursWednesday     = c.openingHoursWednesday,
                openingHoursThursday      = c.openingHoursThursday,
                openingHoursFriday        = c.openingHoursFriday,
                openingHoursSaturday      = c.openingHoursSaturday,
                openingHoursSunday        = c.openingHoursSunday,
                themePrimaryColor         = c.themePrimaryColor,
                themeShowPrices           = c.themeShowPrices,
                themeShowDescriptions     = c.themeShowDescriptions,
                themeShowPhotos           = c.themeShowPhotos,
                themeContrast             = ThemeContrastInfo.of(c.themePrimaryColor),
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
    // Endereco estruturado + pin do mapa (issue #7). Omitido (null) = preservar.
    @field:Size(max = 9)
    val postalCode: String? = null,
    @field:Size(max = 200)
    val street: String? = null,
    @field:Size(max = 20)
    val streetNumber: String? = null,
    @field:Size(max = 100)
    val addressComplement: String? = null,
    @field:Size(max = 100)
    val neighborhood: String? = null,
    @field:Size(max = 2)
    val stateUf: String? = null,
    // lat/lng validados por range no service (DecimalMin/Max nao suporta Double).
    val restaurantLat: Double? = null,
    val restaurantLng: Double? = null,
    // Tempo estimado por modalidade, em minutos (issue #9). 0..1440.
    // Omitido (null) = preservar. Consistencia min<=max validada no service.
    @field:Min(0) @field:Max(1440)
    val deliveryTimeMinMinutes: Int? = null,
    @field:Min(0) @field:Max(1440)
    val deliveryTimeMaxMinutes: Int? = null,
    @field:Min(0) @field:Max(1440)
    val pickupTimeMinMinutes: Int? = null,
    @field:Min(0) @field:Max(1440)
    val pickupTimeMaxMinutes: Int? = null,
    @field:Min(0) @field:Max(1440)
    val dineinTimeMinMinutes: Int? = null,
    @field:Min(0) @field:Max(1440)
    val dineinTimeMaxMinutes: Int? = null,
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
    /** api_secret do GA4 Measurement Protocol (write-only): gravado CIFRADO; "" limpa; nunca devolvido no GET. */
    @field:Size(max = 200)
    val googleApiSecret: String? = null,
    val conversionTrackingEnabled: Boolean? = null,
    // Copiloto do dono: IA (Fase 4.1). Omitido (null) = preservar valor atual.
    val aiEnabled: Boolean? = null,
    @field:Size(max = 4000)
    val aiSystemPrompt: String? = null,
    @field:Min(1) @field:Max(1000)
    val aiDailyLimit: Int? = null,
    // Hardening do Copiloto (Fase 4.2): omitido (null) = preservar valor atual.
    @field:Min(100) @field:Max(10000)
    val aiMaxMessageLength: Int? = null,
    @field:Size(max = 4000)
    val aiBlockedPatterns: String? = null,
    // Bot WhatsApp inbound (Fase 4.3): omitido (null) = preservar valor atual.
    val botEnabled: Boolean? = null,
    @field:Size(max = 4000)
    val botSystemPrompt: String? = null,
    @field:Size(max = 50)
    val botHandoffKeyword: String? = null,
    @field:Size(max = 1000)
    val botWelcomeMessage: String? = null,
    @field:Size(max = 1000)
    val botHandoffMessage: String? = null,
    @field:Size(max = 20)
    val openingHoursMonday: String? = null,
    @field:Size(max = 20)
    val openingHoursTuesday: String? = null,
    @field:Size(max = 20)
    val openingHoursWednesday: String? = null,
    @field:Size(max = 20)
    val openingHoursThursday: String? = null,
    @field:Size(max = 20)
    val openingHoursFriday: String? = null,
    @field:Size(max = 20)
    val openingHoursSaturday: String? = null,
    @field:Size(max = 20)
    val openingHoursSunday: String? = null,
    // Tema do cardapio publico (Fase CONFIG-B, issue #12): omitido (null) = preservar.
    // Formato do hex validado no service (ColorContrast); "" limpa a cor (volta ao default).
    @field:Size(max = 7)
    val themePrimaryColor: String? = null,
    val themeShowPrices: Boolean? = null,
    val themeShowDescriptions: Boolean? = null,
    val themeShowPhotos: Boolean? = null,
)
