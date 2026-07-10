package com.menuflow.platform

import com.menuflow.model.control.SubscriptionPlan

/**
 * Modulos OPCIONAIS do MenuFlow (nunca o core: auth, cardapio, PDV, KDS, caixa —
 * esses jamais sao gateados). Cada modulo tem um default POR PLANO; um override por
 * tenant na tabela tenant_module (banco de CONTROLE) vence o default quando existe.
 *
 * Regra (licao Posthumus): entitlement mora no controle, separado do rotulo do plano.
 * BASIC = so core; modulos pagos OFF por default. Sem linha na tabela => vale o
 * default do plano definido aqui.
 *
 * O nome do enum (name) e a chave persistida em tenant_module.module_key (VARCHAR).
 */
enum class ModuleKey(val label: String, val paidPlans: Set<SubscriptionPlan>) {
    IFOOD("iFood", setOf(SubscriptionPlan.PRO, SubscriptionPlan.ENTERPRISE)),
    OPEN_DELIVERY("Open Delivery", setOf(SubscriptionPlan.PRO, SubscriptionPlan.ENTERPRISE)),
    AI_COPILOT("Copiloto IA", setOf(SubscriptionPlan.PRO, SubscriptionPlan.ENTERPRISE)),
    WHATSAPP_BOT("Bot WhatsApp", setOf(SubscriptionPlan.PRO, SubscriptionPlan.ENTERPRISE)),
    DELIVERY("Modulo Entrega", setOf(SubscriptionPlan.PRO, SubscriptionPlan.ENTERPRISE)),
    GROWTH("Growth Center", setOf(SubscriptionPlan.PRO, SubscriptionPlan.ENTERPRISE)),
    LOYALTY("Fidelidade", setOf(SubscriptionPlan.PRO, SubscriptionPlan.ENTERPRISE)),

    // Fase 8.0 — Central de Trafego Pago (Meta Ads). Modulo pago (PRO/ENTERPRISE),
    // OFF por default no BASIC. O teto de verba por tenant (max_daily_budget_cents)
    // mora em tenant_module.limits_json quando a Fase 8.2 (criar/pausar campanha)
    // chegar — sem coluna dedicada agora, pois nada gasta verba na fase read-only.
    ADS("Trafego Pago", setOf(SubscriptionPlan.PRO, SubscriptionPlan.ENTERPRISE));

    /** Default do modulo para um plano: habilitado se o plano paga por ele. */
    fun defaultEnabledFor(plan: SubscriptionPlan): Boolean = plan in paidPlans
}
