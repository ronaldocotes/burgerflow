-- Fase 6.1 — configuracao de entrega do restaurante (linha unica de tenant_config).
-- delivery_mode: frota propria, terceirizada (iFood/Rappi) ou hibrida.
-- auto_assign_enabled liga o despacho automatico por proximidade (Haversine).
-- offer_timeout_seconds = janela para o motoboy aceitar antes de expirar.
-- max_offer_radius_km = raio maximo para ofertar a um entregador.
-- base_fee + per_km = tarifa de entrega paga (em centavos, nunca float).
ALTER TABLE tenant_config
  ADD COLUMN IF NOT EXISTS delivery_mode          VARCHAR(12) NOT NULL DEFAULT 'OWN_FLEET'
                           CHECK (delivery_mode IN ('OWN_FLEET','THIRD_PARTY','HYBRID')),
  ADD COLUMN IF NOT EXISTS auto_assign_enabled    BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS offer_timeout_seconds  INT NOT NULL DEFAULT 45,
  ADD COLUMN IF NOT EXISTS max_offer_radius_km    NUMERIC(4,1) NOT NULL DEFAULT 10.0,
  ADD COLUMN IF NOT EXISTS delivery_base_fee_cents BIGINT NOT NULL DEFAULT 500,
  ADD COLUMN IF NOT EXISTS delivery_fee_per_km_cents BIGINT NOT NULL DEFAULT 200;
