-- Fase B1 -- despacho de entrega por GRUPO (broadcast). A oferta deixa de ser para
-- UM motoboy pre-escolhido (auto-assign da Fase 6.1) e passa a ser publicada num
-- grupo de WhatsApp; o vencedor (accepted_by_driver_id) emerge do aceite ATOMICO.
-- O aceite usa CAS puro em SQL (UPDATE ... WHERE status='OFFERED') -- SEM coluna de
-- versao/lock otimista: uma unica linha afetada = venceu; zero = corrida ja fechada.
-- Por isso driver_id passa a ser NULLABLE: a oferta de grupo nasce sem motoboy. As
-- ofertas legadas (auto-assign) continuam gravando driver_id e group_jid = NULL.
ALTER TABLE delivery_offers
  ALTER COLUMN driver_id DROP NOT NULL;

ALTER TABLE delivery_offers
  ADD COLUMN IF NOT EXISTS payout_cents          BIGINT,
  ADD COLUMN IF NOT EXISTS distance_meters       BIGINT,
  ADD COLUMN IF NOT EXISTS neighborhood_label    VARCHAR(60),
  ADD COLUMN IF NOT EXISTS group_jid             VARCHAR(100),
  ADD COLUMN IF NOT EXISTS waha_poll_message_id  VARCHAR(100),
  ADD COLUMN IF NOT EXISTS attempt               INT         NOT NULL DEFAULT 1,
  ADD COLUMN IF NOT EXISTS accept_code           VARCHAR(8),
  ADD COLUMN IF NOT EXISTS accepted_by_driver_id UUID        REFERENCES delivery_drivers(id),
  ADD COLUMN IF NOT EXISTS accepted_at           TIMESTAMPTZ;

-- Codigo de aceite unico ENTRE as ofertas vivas (evita colisao de codigo no grupo).
CREATE UNIQUE INDEX IF NOT EXISTS uq_delivery_offer_accept_code
  ON delivery_offers(accept_code) WHERE status = 'OFFERED' AND accept_code IS NOT NULL;

-- Indice para o scheduler achar rapidamente as ofertas de GRUPO vencidas a reofertar.
CREATE INDEX IF NOT EXISTS idx_delivery_offers_group_expires
  ON delivery_offers(expires_at) WHERE status = 'OFFERED' AND group_jid IS NOT NULL;
