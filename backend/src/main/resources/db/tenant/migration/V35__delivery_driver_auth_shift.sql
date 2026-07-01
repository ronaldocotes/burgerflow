-- Fase 6.1 — elo de autenticacao, turno e ultima localizacao do entregador.
-- user_id liga o DeliveryDriver ao User do banco de controle (papel DRIVER); o app
-- do motoboy resolve o proprio driver por esse elo. active_shift = motoboy online e
-- disponivel para receber ofertas. last_lat/last_lng/last_location_at guardam a
-- ultima posicao reportada (mapa ao vivo e auto-assign). battery_pct e telemetria.
ALTER TABLE delivery_drivers
  ADD COLUMN IF NOT EXISTS user_id      UUID,
  ADD COLUMN IF NOT EXISTS active_shift BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS last_lat     NUMERIC(9,6),
  ADD COLUMN IF NOT EXISTS last_lng     NUMERIC(9,6),
  ADD COLUMN IF NOT EXISTS last_location_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS battery_pct  INT;

-- Um User (motoboy) so pode estar ligado a um DeliveryDriver (elo 1:1). Indice
-- parcial para permitir varios drivers legados ainda sem user_id (NULL).
CREATE UNIQUE INDEX IF NOT EXISTS uq_delivery_drivers_user_id
  ON delivery_drivers(user_id) WHERE user_id IS NOT NULL;

-- Busca quente do auto-assign: entregadores online e ativos.
CREATE INDEX IF NOT EXISTS idx_delivery_drivers_shift_active
  ON delivery_drivers(active_shift, active) WHERE active_shift = TRUE AND active = TRUE;
