-- Fase A2 -- separacao taxa(cliente) x payout(motoboy) + configuracao do despacho
-- por grupo de WhatsApp. Tudo na linha unica de tenant_config (db-per-tenant).
-- Dinheiro SEMPRE em centavos (BIGINT, nunca float). Os *_payout_* sao nullable:
-- null = usar o valor da taxa correspondente (payout espelha a taxa por padrao).
ALTER TABLE tenant_config
  ADD COLUMN IF NOT EXISTS delivery_free_radius_meters    BIGINT      NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS delivery_min_fee_cents         BIGINT      NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS delivery_base_payout_cents     BIGINT,
  ADD COLUMN IF NOT EXISTS delivery_per_km_payout_cents   BIGINT,
  ADD COLUMN IF NOT EXISTS delivery_min_payout_cents      BIGINT,
  ADD COLUMN IF NOT EXISTS dispatch_enabled               BOOLEAN     NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS motoboy_group_jid              VARCHAR(100),
  ADD COLUMN IF NOT EXISTS dispatch_offer_timeout_seconds INT         NOT NULL DEFAULT 90,
  ADD COLUMN IF NOT EXISTS dispatch_ready_lead_minutes    INT         NOT NULL DEFAULT 8,
  ADD COLUMN IF NOT EXISTS dispatch_max_attempts          INT         NOT NULL DEFAULT 3,
  ADD COLUMN IF NOT EXISTS restaurant_lat                 DOUBLE PRECISION,
  ADD COLUMN IF NOT EXISTS restaurant_lng                 DOUBLE PRECISION,
  ADD COLUMN IF NOT EXISTS distance_provider              VARCHAR(20) NOT NULL DEFAULT 'HAVERSINE';
