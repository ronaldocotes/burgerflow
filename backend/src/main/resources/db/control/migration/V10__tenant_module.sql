-- Entitlements de módulos por empresa (Fase 1 do painel super-admin)
-- Modelo: plan defaults no código + override por tenant nesta tabela.
-- Sem linha = vale o default do plano (BASIC = só core; módulos pagos OFF).
-- NÃO gatear API core (auth, cardápio, PDV, KDS, caixa).
CREATE TABLE tenant_module (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    module_key      VARCHAR(40) NOT NULL,     -- enum no código: IFOOD/OPEN_DELIVERY/AI_COPILOT/WHATSAPP_BOT/DELIVERY/GROWTH/LOYALTY
    enabled         BOOLEAN     NOT NULL,
    limits_json     JSONB,                    -- ex.: {"ai_monthly_token_cap": 2000000}
    updated_by_user_id UUID NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_tenant_module UNIQUE (tenant_id, module_key)
);

CREATE INDEX idx_tenant_module_tenant ON tenant_module(tenant_id);

COMMENT ON TABLE tenant_module IS 'Override de entitlement de módulo por empresa. Ausência de linha = default do plano.';
COMMENT ON COLUMN tenant_module.module_key IS 'IFOOD | OPEN_DELIVERY | AI_COPILOT | WHATSAPP_BOT | DELIVERY | GROWTH | LOYALTY';
COMMENT ON COLUMN tenant_module.limits_json IS 'Limites específicos do módulo, ex.: {"ai_monthly_token_cap": 2000000}';
