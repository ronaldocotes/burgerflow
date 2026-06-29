-- V25__campaigns.sql
-- Fase 3.4 — RFV + Campanhas WhatsApp (banco do TENANT).

-- Opt-in de marketing por cliente. Pre-requisito de seguranca: so disparamos
-- campanha proativa para quem deu opt-in explicito (mitiga ban do WAHA).
ALTER TABLE customers ADD COLUMN IF NOT EXISTS marketing_opt_in BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE customers ADD COLUMN IF NOT EXISTS opt_in_at TIMESTAMPTZ;
ALTER TABLE customers ADD COLUMN IF NOT EXISTS opt_out_at TIMESTAMPTZ;

-- Config de WAHA e campanha no tenant (1 linha por restaurante, db-per-tenant).
ALTER TABLE tenant_config ADD COLUMN IF NOT EXISTS waha_primary_phone VARCHAR(20);
ALTER TABLE tenant_config ADD COLUMN IF NOT EXISTS waha_fallback_phone VARCHAR(20);
ALTER TABLE tenant_config ADD COLUMN IF NOT EXISTS campaign_daily_limit INTEGER NOT NULL DEFAULT 50;
ALTER TABLE tenant_config ADD COLUMN IF NOT EXISTS campaign_delay_min_seconds INTEGER NOT NULL DEFAULT 15;
ALTER TABLE tenant_config ADD COLUMN IF NOT EXISTS campaign_delay_max_seconds INTEGER NOT NULL DEFAULT 45;

-- Campanhas
CREATE TABLE campaigns (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name VARCHAR(200) NOT NULL,
  message_template TEXT NOT NULL,  -- suporta {nome}, {pontos}, {dias} como variaveis
  segment VARCHAR(50) NOT NULL,    -- RFV_INACTIVE, RFV_AT_RISK, RFV_LOYAL, ALL_OPT_IN, CUSTOM
  segment_params JSONB,            -- ex: {"inactive_days": 30, "min_orders": 3}
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',  -- DRAFT, RUNNING, PAUSED, COMPLETED, FAILED
  scheduled_at TIMESTAMPTZ,
  started_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  total_recipients INTEGER NOT NULL DEFAULT 0,
  sent_count INTEGER NOT NULL DEFAULT 0,
  failed_count INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Envios individuais por campanha (rastreamento)
CREATE TABLE campaign_sends (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  campaign_id UUID NOT NULL REFERENCES campaigns(id),
  customer_id UUID NOT NULL REFERENCES customers(id),
  phone VARCHAR(20) NOT NULL,
  message TEXT NOT NULL,           -- mensagem apos substituicao de variaveis
  status VARCHAR(20) NOT NULL DEFAULT 'QUEUED',  -- QUEUED, SENT, FAILED, OPT_OUT
  sent_at TIMESTAMPTZ,
  error_message VARCHAR(500),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_campaign_sends_campaign ON campaign_sends(campaign_id);
CREATE INDEX idx_campaign_sends_customer ON campaign_sends(customer_id);
CREATE UNIQUE INDEX uq_campaign_send ON campaign_sends(campaign_id, customer_id);
