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

    // --- Endereco estruturado da loja (issue #7). Preenchido pela busca de CEP. ---
    /** CEP no formato "00000-000". */
    @Column(name = "postal_code", length = 9)
    var postalCode: String? = null,

    /** Logradouro (rua/avenida). */
    @Column(name = "street", length = 200)
    var street: String? = null,

    /** Numero (texto: aceita "S/N", "123A"). */
    @Column(name = "street_number", length = 20)
    var streetNumber: String? = null,

    /** Complemento do endereco. */
    @Column(name = "address_complement", length = 100)
    var addressComplement: String? = null,

    /** Bairro. */
    @Column(name = "neighborhood", length = 100)
    var neighborhood: String? = null,

    /** UF (SP, RJ, ...). */
    @Column(name = "state_uf", length = 2)
    var stateUf: String? = null,

    // --- Tempo estimado (min/max, em minutos) por modalidade (issue #9). ---
    /** Promessa de prazo de DELIVERY exibida no cardapio publico. */
    @Column(name = "delivery_time_min_minutes", nullable = false)
    var deliveryTimeMinMinutes: Int = 30,

    @Column(name = "delivery_time_max_minutes", nullable = false)
    var deliveryTimeMaxMinutes: Int = 60,

    /** Promessa de prazo de RETIRADA (pickup). */
    @Column(name = "pickup_time_min_minutes", nullable = false)
    var pickupTimeMinMinutes: Int = 15,

    @Column(name = "pickup_time_max_minutes", nullable = false)
    var pickupTimeMaxMinutes: Int = 30,

    /** Promessa de prazo de CONSUMO LOCAL (dine-in). */
    @Column(name = "dinein_time_min_minutes", nullable = false)
    var dineinTimeMinMinutes: Int = 10,

    @Column(name = "dinein_time_max_minutes", nullable = false)
    var dineinTimeMaxMinutes: Int = 20,

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

    // --- Copiloto do dono: IA (Fase 4.1) ---
    /** Liga/desliga o Copiloto de IA do restaurante. */
    @Column(name = "ai_enabled", nullable = false)
    var aiEnabled: Boolean = false,

    /** Personalizacao opcional do prompt de sistema do copiloto (tom, regras do dono). */
    @Column(name = "ai_system_prompt", columnDefinition = "text")
    var aiSystemPrompt: String? = null,

    /** Teto de perguntas/dia ao copiloto por tenant (anti-abuso/custo). */
    @Column(name = "ai_daily_limit", nullable = false)
    var aiDailyLimit: Int = 30,

    // --- Hardening do Copiloto: guardrails de prompt injection (Fase 4.2) ---
    /** Teto de caracteres da mensagem do dono; acima disso truncamos silenciosamente. */
    @Column(name = "ai_max_message_length", nullable = false)
    var aiMaxMessageLength: Int = 2000,

    /**
     * JSON array de regexes EXTRAS bloqueados por este restaurante. Os padroes default
     * (jailbreak classico, injecao de role, extracao de prompt, escalonamento) sao
     * hardcoded na aplicacao; esta coluna apenas acrescenta. Null/vazio = so os default.
     */
    @Column(name = "ai_blocked_patterns", columnDefinition = "text")
    var aiBlockedPatterns: String? = null,

    // --- Bot WhatsApp inbound (Fase 4.3) ---
    /** Liga/desliga o atendente virtual (bot) no WhatsApp do restaurante. */
    @Column(name = "bot_enabled", nullable = false)
    var botEnabled: Boolean = false,

    /** Personalizacao opcional do prompt de sistema do bot (tom, regras do restaurante). */
    @Column(name = "bot_system_prompt", columnDefinition = "text")
    var botSystemPrompt: String? = null,

    /** Palavra-chave que o cliente digita para falar com um humano (default "atendente"). */
    @Column(name = "bot_handoff_keyword", length = 50)
    var botHandoffKeyword: String? = "atendente",

    /** Saudacao inicial do bot. */
    @Column(name = "bot_welcome_message", columnDefinition = "text")
    var botWelcomeMessage: String? = "Olá! Sou o assistente virtual. Como posso ajudar?",

    /** Mensagem enviada ao cliente ao transferir para um humano. */
    @Column(name = "bot_handoff_message", columnDefinition = "text")
    var botHandoffMessage: String? = "Transferindo para um atendente humano. Aguarde!",

    // Horarios de funcionamento por dia ("HH:mm-HH:mm"); null = fechado naquele dia.
    @Column(name = "opening_hours_monday", length = 20)
    var openingHoursMonday: String? = null,

    @Column(name = "opening_hours_tuesday", length = 20)
    var openingHoursTuesday: String? = null,

    @Column(name = "opening_hours_wednesday", length = 20)
    var openingHoursWednesday: String? = null,

    @Column(name = "opening_hours_thursday", length = 20)
    var openingHoursThursday: String? = null,

    @Column(name = "opening_hours_friday", length = 20)
    var openingHoursFriday: String? = null,

    @Column(name = "opening_hours_saturday", length = 20)
    var openingHoursSaturday: String? = null,

    @Column(name = "opening_hours_sunday", length = 20)
    var openingHoursSunday: String? = null,

    // --- Entrega (Fase 6.1) ---
    /** Modo de operação da entrega: OWN_FLEET, THIRD_PARTY ou HYBRID. */
    @Column(name = "delivery_mode", nullable = false, length = 12)
    var deliveryMode: String = "OWN_FLEET",

    /** Liga o despacho automático por proximidade (Haversine). */
    @Column(name = "auto_assign_enabled", nullable = false)
    var autoAssignEnabled: Boolean = false,

    /** Janela (segundos) para o motoboy aceitar a oferta antes de expirar. */
    @Column(name = "offer_timeout_seconds", nullable = false)
    var offerTimeoutSeconds: Int = 45,

    /** Raio máximo (km) para ofertar a entrega a um entregador. */
    @Column(name = "max_offer_radius_km", nullable = false)
    var maxOfferRadiusKm: Double = 10.0,

    /** Tarifa base de entrega, em centavos (nunca float para dinheiro). */
    @Column(name = "delivery_base_fee_cents", nullable = false)
    var deliveryBaseFeeCents: Long = 500,

    /** Tarifa por km rodado, em centavos. */
    @Column(name = "delivery_fee_per_km_cents", nullable = false)
    var deliveryFeePerKmCents: Long = 200,

    // --- Fase A2: taxa(cliente) x payout(motoboy) + raio gratis + piso ---
    /** Raio (metros) isento de cobranca por km: km cobravel = km - esse raio. */
    @Column(name = "delivery_free_radius_meters", nullable = false)
    var deliveryFreeRadiusMeters: Long = 0,

    /** Piso da tarifa cobrada do cliente (centavos). */
    @Column(name = "delivery_min_fee_cents", nullable = false)
    var deliveryMinFeeCents: Long = 0,

    /** Base do REPASSE ao motoboy (centavos); null = espelha a tarifa base. */
    @Column(name = "delivery_base_payout_cents")
    var deliveryBasePayoutCents: Long? = null,

    /** Repasse por km ao motoboy (centavos); null = espelha a tarifa por km. */
    @Column(name = "delivery_per_km_payout_cents")
    var deliveryPerKmPayoutCents: Long? = null,

    /** Piso do repasse ao motoboy (centavos); null = espelha o piso da tarifa. */
    @Column(name = "delivery_min_payout_cents")
    var deliveryMinPayoutCents: Long? = null,

    // --- Fase B1: despacho por grupo de WhatsApp ---
    /** Liga o despacho automatico por grupo de WhatsApp (broadcast + aceite atomico). */
    @Column(name = "dispatch_enabled", nullable = false)
    var dispatchEnabled: Boolean = false,

    /** JID do grupo de WhatsApp dos motoboys onde a oferta e publicada. */
    @Column(name = "motoboy_group_jid", length = 100)
    var motoboyGroupJid: String? = null,

    /** Janela (segundos) para alguem do grupo aceitar antes de expirar/reofertar. */
    @Column(name = "dispatch_offer_timeout_seconds", nullable = false)
    var dispatchOfferTimeoutSeconds: Int = 90,

    /** Antecedencia (minutos) para ofertar antes do preparo terminar (motoboy chega a tempo). */
    @Column(name = "dispatch_ready_lead_minutes", nullable = false)
    var dispatchReadyLeadMinutes: Int = 8,

    /** Numero maximo de tentativas de reoferta antes de escalar ao dono. */
    @Column(name = "dispatch_max_attempts", nullable = false)
    var dispatchMaxAttempts: Int = 3,

    /** Latitude do restaurante (origem da corrida) — usada no calculo de distancia. */
    @Column(name = "restaurant_lat")
    var restaurantLat: Double? = null,

    /** Longitude do restaurante (origem da corrida). */
    @Column(name = "restaurant_lng")
    var restaurantLng: Double? = null,

    /** Provedor de distancia: HAVERSINE (fallback), GOOGLE (rota real de moto, 10k/mes) ou OSRM (self-hosted A1, custo zero). Ver DistanceService para a cadeia de fallback. */
    @Column(name = "distance_provider", nullable = false, length = 20)
    var distanceProvider: String = "HAVERSINE",

    /** Telefone do DONO para escalacao do despacho (ninguem aceitou). Null = so loga. */
    @Column(name = "owner_phone", length = 20)
    var ownerPhone: String? = null,

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
