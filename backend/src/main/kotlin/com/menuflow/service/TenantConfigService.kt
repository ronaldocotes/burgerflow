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
        // Endereco estruturado + pin do mapa (issue #7): omitido (null) preserva.
        req.postalCode?.let        { config.postalCode        = it.trim().ifBlank { null } }
        req.street?.let            { config.street            = it.trim().ifBlank { null } }
        req.streetNumber?.let      { config.streetNumber      = it.trim().ifBlank { null } }
        req.addressComplement?.let { config.addressComplement = it.trim().ifBlank { null } }
        req.neighborhood?.let      { config.neighborhood      = it.trim().ifBlank { null } }
        req.stateUf?.let           { config.stateUf           = it.trim().uppercase().ifBlank { null } }
        req.restaurantLat?.let {
            require(it in -90.0..90.0) { "restaurantLat deve estar entre -90 e 90" }
            config.restaurantLat = it
        }
        req.restaurantLng?.let {
            require(it in -180.0..180.0) { "restaurantLng deve estar entre -180 e 180" }
            config.restaurantLng = it
        }
        // Tempo por modalidade (issue #9): omitido (null) preserva; enviado sobrescreve.
        req.deliveryTimeMinMinutes?.let { config.deliveryTimeMinMinutes = it }
        req.deliveryTimeMaxMinutes?.let { config.deliveryTimeMaxMinutes = it }
        req.pickupTimeMinMinutes?.let   { config.pickupTimeMinMinutes   = it }
        req.pickupTimeMaxMinutes?.let   { config.pickupTimeMaxMinutes   = it }
        req.dineinTimeMinMinutes?.let   { config.dineinTimeMinMinutes   = it }
        req.dineinTimeMaxMinutes?.let   { config.dineinTimeMaxMinutes   = it }
        require(config.deliveryTimeMinMinutes <= config.deliveryTimeMaxMinutes) {
            "tempo minimo de delivery nao pode ser maior que o maximo"
        }
        require(config.pickupTimeMinMinutes <= config.pickupTimeMaxMinutes) {
            "tempo minimo de retirada nao pode ser maior que o maximo"
        }
        require(config.dineinTimeMinMinutes <= config.dineinTimeMaxMinutes) {
            "tempo minimo de consumo local nao pode ser maior que o maximo"
        }
        // Alíquotas do DRE: omitido (null) preserva; enviado sobrescreve.
        req.marketplaceFeePct?.let { config.marketplaceFeePct = it }
        req.cardFeePct?.let        { config.cardFeePct        = it }
        req.taxPct?.let            { config.taxPct            = it }
        // Fidelidade (Fase 3.3): omitido (null) preserva; enviado sobrescreve.
        req.loyaltyEnabled?.let           { config.loyaltyEnabled          = it }
        req.loyaltyPointsPerReal?.let     { config.loyaltyPointsPerReal    = it }
        req.loyaltyRewardThreshold?.let   { config.loyaltyRewardThreshold  = it }
        req.loyaltyRewardDescription?.let { config.loyaltyRewardDescription = it.trim().ifBlank { null } }
        // Campanhas WhatsApp + WAHA (Fase 3.4): omitido (null) preserva; enviado sobrescreve.
        req.wahaPrimaryPhone?.let        { config.wahaPrimaryPhone        = it.trim().ifBlank { null } }
        req.wahaFallbackPhone?.let       { config.wahaFallbackPhone       = it.trim().ifBlank { null } }
        req.campaignDailyLimit?.let      { config.campaignDailyLimit      = it }
        req.campaignDelayMinSeconds?.let { config.campaignDelayMinSeconds = it }
        req.campaignDelayMaxSeconds?.let { config.campaignDelayMaxSeconds = it }
        // Recuperacao de carrinho abandonado (Fase 3.5): omitido (null) preserva; enviado sobrescreve.
        req.cartRecoveryEnabled?.let      { config.cartRecoveryEnabled      = it }
        req.cartRecoveryDelayMinutes?.let { config.cartRecoveryDelayMinutes = it }
        req.cartRecoveryMessage?.let      { config.cartRecoveryMessage      = it.trim().ifBlank { null } }
        req.cartRecoveryExpiryHours?.let  { config.cartRecoveryExpiryHours  = it }
        // Rastreamento de conversao (Fase 3.7): omitido (null) preserva; enviado sobrescreve.
        // Enviar "" limpa o campo (ifBlank -> null), inclusive o token da Meta.
        req.metaPixelId?.let           { config.metaPixelId         = it.trim().ifBlank { null } }
        req.metaAccessToken?.let       { config.metaAccessToken     = it.trim().ifBlank { null } }
        req.metaTestEventCode?.let     { config.metaTestEventCode   = it.trim().ifBlank { null } }
        req.googleSgtmUrl?.let         { config.googleSgtmUrl       = it.trim().ifBlank { null } }
        req.googleMeasurementId?.let   { config.googleMeasurementId = it.trim().ifBlank { null } }
        req.conversionTrackingEnabled?.let { config.conversionTrackingEnabled = it }
        // Copiloto do dono: IA (Fase 4.1): omitido (null) preserva; enviado sobrescreve.
        req.aiEnabled?.let      { config.aiEnabled      = it }
        req.aiSystemPrompt?.let { config.aiSystemPrompt = it.trim().ifBlank { null } }
        req.aiDailyLimit?.let   { config.aiDailyLimit   = it }
        // Hardening do Copiloto (Fase 4.2): omitido (null) preserva; "" limpa os padroes extras.
        req.aiMaxMessageLength?.let { config.aiMaxMessageLength = it }
        req.aiBlockedPatterns?.let  { config.aiBlockedPatterns  = it.trim().ifBlank { null } }
        // Bot WhatsApp inbound (Fase 4.3): omitido (null) preserva; "" limpa textos opcionais.
        req.botEnabled?.let         { config.botEnabled         = it }
        req.botSystemPrompt?.let    { config.botSystemPrompt    = it.trim().ifBlank { null } }
        req.botHandoffKeyword?.let  { config.botHandoffKeyword  = it.trim().ifBlank { null } }
        req.botWelcomeMessage?.let  { config.botWelcomeMessage  = it.trim().ifBlank { null } }
        req.botHandoffMessage?.let  { config.botHandoffMessage  = it.trim().ifBlank { null } }
        req.openingHoursMonday?.let    { config.openingHoursMonday    = it.trim().ifBlank { null } }
        req.openingHoursTuesday?.let   { config.openingHoursTuesday   = it.trim().ifBlank { null } }
        req.openingHoursWednesday?.let { config.openingHoursWednesday = it.trim().ifBlank { null } }
        req.openingHoursThursday?.let  { config.openingHoursThursday  = it.trim().ifBlank { null } }
        req.openingHoursFriday?.let    { config.openingHoursFriday    = it.trim().ifBlank { null } }
        req.openingHoursSaturday?.let  { config.openingHoursSaturday  = it.trim().ifBlank { null } }
        req.openingHoursSunday?.let    { config.openingHoursSunday    = it.trim().ifBlank { null } }
        return TenantConfigResponse.from(repository.save(config))
    }
}
