-- V28: despachos de conversao (Meta CAPI + Google sGTM) — Fase 3.7.
-- Cada pedido pago gera ate um despacho por plataforma (META, GOOGLE). O envio
-- e idempotente: o indice unico (order_id, platform) garante que o mesmo pedido
-- nunca conta duas vezes na mesma plataforma. event_id deduplica do lado da Meta
-- (browser pixel + CAPI compartilham "order-{orderId}").

CREATE TABLE conversion_dispatches (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  order_id UUID NOT NULL REFERENCES orders(id),
  platform VARCHAR(20) NOT NULL,                  -- 'META' ou 'GOOGLE'
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING, SENT, FAILED, SKIPPED
  event_id VARCHAR(100),                          -- ex: "order-{orderId}"
  payload_hash VARCHAR(64),                       -- SHA-256 do payload enviado (auditoria/dedup)
  response_code INTEGER,
  response_body TEXT,
  attempts INTEGER NOT NULL DEFAULT 0,
  last_attempt_at TIMESTAMPTZ,
  sent_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_conv_dispatches_order ON conversion_dispatches(order_id);
CREATE INDEX idx_conv_dispatches_status ON conversion_dispatches(status, platform);
CREATE UNIQUE INDEX uq_conv_dispatch_order_platform ON conversion_dispatches(order_id, platform);

-- Configuracao de rastreamento de conversao por tenant. Token da Meta e segredo:
-- nunca e devolvido no GET /config (somente o flag hasMetaToken).
ALTER TABLE tenant_config
  ADD COLUMN IF NOT EXISTS meta_pixel_id VARCHAR(100),
  ADD COLUMN IF NOT EXISTS meta_access_token TEXT,
  ADD COLUMN IF NOT EXISTS meta_test_event_code VARCHAR(50),   -- null em producao
  ADD COLUMN IF NOT EXISTS google_sgtm_url TEXT,               -- ex: https://sgtm.exemplo.com
  ADD COLUMN IF NOT EXISTS google_measurement_id VARCHAR(50),  -- ex: G-XXXXXXXX
  ADD COLUMN IF NOT EXISTS conversion_tracking_enabled BOOLEAN NOT NULL DEFAULT FALSE;
