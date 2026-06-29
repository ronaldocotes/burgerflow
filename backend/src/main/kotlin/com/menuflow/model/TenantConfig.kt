package com.menuflow.model

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Configurações operacionais do tenant. Vive no banco do TENANT (db-per-tenant),
 * então há no máximo uma linha por restaurante — não precisa de coluna de escopo.
 *
 * Hoje guarda apenas o aceite automático de pedidos (pedido nasce em PREPARING,
 * indo direto para a cozinha sem ação manual). A tabela foi desenhada para
 * crescer: novos toggles entram como colunas aditivas nesta mesma linha.
 */
@Entity
@Table(name = "tenant_config")
data class TenantConfig(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    /** Pedido novo nasce em PREPARING (já na cozinha) em vez de PENDING. */
    @Column(name = "auto_accept_orders", nullable = false)
    var autoAcceptOrders: Boolean = false,

    /** Chave PIX estatica do restaurante (nullable: pode nao ter PIX). */
    @Column(name = "pix_key", nullable = true, length = 140)
    var pixKey: String? = null,

    // --- Marca/vitrine do restaurante (cardapio publico). Todos nullable. ---
    /** Nome de exibicao do restaurante no cardapio publico. */
    @Column(name = "restaurant_name", length = 100)
    var restaurantName: String? = null,

    /** URL do logo do restaurante. */
    @Column(name = "logo_url", length = 500)
    var logoUrl: String? = null,

    /** URL da imagem de capa/banner. */
    @Column(name = "cover_url", length = 500)
    var coverUrl: String? = null,

    /** Endereco do restaurante (texto livre). */
    @Column(name = "address", length = 200)
    var address: String? = null,

    /** Horario de funcionamento (texto livre). */
    @Column(name = "opening_hours", length = 200)
    var openingHours: String? = null,

    /** Cidade do estabelecimento (usada pelo PIX/exibicao). */
    @Column(name = "merchant_city", length = 50)
    var merchantCity: String? = null,

    /**
     * Id do customer "avulso" fixo do restaurante no Asaas (Fase 2.3). O PDV/balcao
     * nao cadastra cliente por venda; usamos um unico customer por tenant, criado uma
     * vez e reaproveitado. Interno (NAO exposto nos DTOs de config).
     */
    @Column(name = "asaas_customer_id", length = 64)
    var asaasCustomerId: String? = null,

    // --- Alíquotas para o DRE Automático (Fase 3.1). Percentuais, default 0. ---
    /** Alíquota (%) de marketplace (iFood/Rappi) sobre o total de pedidos DELIVERY. */
    @Column(name = "marketplace_fee_pct", nullable = false, precision = 5, scale = 2)
    var marketplaceFeePct: BigDecimal = BigDecimal.ZERO,

    /** Alíquota (%) de cartão sobre o total de pedidos pagos em cartão. */
    @Column(name = "card_fee_pct", nullable = false, precision = 5, scale = 2)
    var cardFeePct: BigDecimal = BigDecimal.ZERO,

    /** Alíquota (%) de impostos sobre a receita bruta. */
    @Column(name = "tax_pct", nullable = false, precision = 5, scale = 2)
    var taxPct: BigDecimal = BigDecimal.ZERO,

    // --- Programa de Fidelidade punch-card (Fase 3.3) ---
    /** Liga/desliga o programa de fidelidade do restaurante. */
    @Column(name = "loyalty_enabled", nullable = false)
    var loyaltyEnabled: Boolean = false,

    /** Pontos creditados por R$1,00 gasto (truncado). */
    @Column(name = "loyalty_points_per_real", nullable = false)
    var loyaltyPointsPerReal: Int = 1,

    /** Pontos necessários para desbloquear 1 recompensa (punch). */
    @Column(name = "loyalty_reward_threshold", nullable = false)
    var loyaltyRewardThreshold: Int = 100,

    /** Texto da recompensa enviado ao cliente ao desbloquear o punch. */
    @Column(name = "loyalty_reward_description", length = 200)
    var loyaltyRewardDescription: String? = "Recompensa desbloqueada!",

    // --- Campanhas WhatsApp + WAHA (Fase 3.4) ---
    /**
     * Sessao WAHA primaria usada para os disparos de campanha (geralmente o numero
     * principal). Passada como `session` ao WAHA; null = sessao "default".
     */
    @Column(name = "waha_primary_phone", length = 20)
    var wahaPrimaryPhone: String? = null,

    /** Sessao WAHA reserva: usada se o disparo pela primaria falhar (failover anti-ban). */
    @Column(name = "waha_fallback_phone", length = 20)
    var wahaFallbackPhone: String? = null,

    /** Teto de destinatarios por campanha/dia (mitiga ban do WAHA). */
    @Column(name = "campaign_daily_limit", nullable = false)
    var campaignDailyLimit: Int = 50,

    /** Piso do delay aleatorio entre mensagens, em segundos. */
    @Column(name = "campaign_delay_min_seconds", nullable = false)
    var campaignDelayMinSeconds: Int = 15,

    /** Teto do delay aleatorio entre mensagens, em segundos. */
    @Column(name = "campaign_delay_max_seconds", nullable = false)
    var campaignDelayMaxSeconds: Int = 45,

    // --- Recuperacao de carrinho abandonado (Fase 3.5) ---
    /** Liga/desliga a recuperacao de carrinho abandonado. */
    @Column(name = "cart_recovery_enabled", nullable = false)
    var cartRecoveryEnabled: Boolean = false,

    /** Atraso (minutos) apos a criacao do pedido antes de mandar a mensagem. */
    @Column(name = "cart_recovery_delay_minutes", nullable = false)
    var cartRecoveryDelayMinutes: Int = 30,

    /** Mensagem de recuperacao com placeholders {nome}, {total} e {link}. */
    @Column(name = "cart_recovery_message", columnDefinition = "text")
    var cartRecoveryMessage: String? =
        "🛒 Olá {nome}! Você deixou itens no carrinho. Que tal finalizar seu pedido de {total}? Acesse: {link}",

    /** Prazo (horas) para o carrinho expirar sem pagamento (nao envia mais depois). */
    @Column(name = "cart_recovery_expiry_hours", nullable = false)
    var cartRecoveryExpiryHours: Int = 2,

    // --- Rastreamento de conversao: Meta CAPI + Google sGTM (Fase 3.7) ---
    /** Pixel ID da Meta (Events Manager). Null = Meta nao configurada. */
    @Column(name = "meta_pixel_id", length = 100)
    var metaPixelId: String? = null,

    /**
     * Access token da Meta CAPI (SEGREDO). Nunca e devolvido no GET /config — a
     * resposta expoe apenas o flag hasMetaToken. Persistido no banco do tenant.
     */
    @Column(name = "meta_access_token", columnDefinition = "text")
    var metaAccessToken: String? = null,

    /** test_event_code da Meta: direciona o evento para o teste do Events Manager. Null em producao. */
    @Column(name = "meta_test_event_code", length = 50)
    var metaTestEventCode: String? = null,

    /** URL do sGTM (Server-Side Google Tag Manager) do restaurante. Null = Google nao configurado. */
    @Column(name = "google_sgtm_url", columnDefinition = "text")
    var googleSgtmUrl: String? = null,

    /** Measurement ID GA4 (ex.: G-XXXXXXXX) usado no Measurement Protocol. */
    @Column(name = "google_measurement_id", length = 50)
    var googleMeasurementId: String? = null,

    /** Liga/desliga o rastreamento de conversao (master switch). */
    @Column(name = "conversion_tracking_enabled", nullable = false)
    var conversionTrackingEnabled: Boolean = false,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun preUpdate() {
        updatedAt = Instant.now()
    }
}
