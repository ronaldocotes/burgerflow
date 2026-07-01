-- Fase 6.1 — ofertas de entrega (auto-assign). Uma oferta e enviada a UM entregador
-- proximo; ele aceita/recusa antes de expires_at. O EXCLUDE garante no maximo UMA
-- oferta OFFERED (viva) por pedido de cada vez, evitando double-dispatch concorrente.
CREATE TABLE delivery_offers (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  order_id     UUID NOT NULL REFERENCES orders(id),
  driver_id    UUID NOT NULL REFERENCES delivery_drivers(id),
  status       VARCHAR(10) NOT NULL DEFAULT 'OFFERED'
               CHECK (status IN ('OFFERED','ACCEPTED','REJECTED','EXPIRED')),
  fee_cents    BIGINT NOT NULL DEFAULT 0,
  distance_km  NUMERIC(6,2),
  offered_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  responded_at TIMESTAMPTZ,
  expires_at   TIMESTAMPTZ NOT NULL,
  CONSTRAINT uq_active_offer_per_order
    EXCLUDE USING btree (order_id WITH =) WHERE (status = 'OFFERED')
);
CREATE INDEX idx_delivery_offers_driver_status ON delivery_offers(driver_id, status);
CREATE INDEX idx_delivery_offers_expires ON delivery_offers(expires_at) WHERE status = 'OFFERED';
