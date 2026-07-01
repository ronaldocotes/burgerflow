-- Fase 6.1 — endereco de entrega e geolocalizacao do pedido de delivery.
-- Todas as colunas sao nullable: pedido de balcao/mesa nao tem endereco, e o geocode
-- (lat/lng) pode nao estar disponivel no momento da criacao. delivery_geocode_source
-- registra a origem da coordenada (ex.: VIACEP, GOOGLE, MANUAL) para auditoria.
ALTER TABLE orders
  ADD COLUMN IF NOT EXISTS delivery_recipient_name VARCHAR(120),
  ADD COLUMN IF NOT EXISTS delivery_phone          VARCHAR(20),
  ADD COLUMN IF NOT EXISTS delivery_cep            VARCHAR(9),
  ADD COLUMN IF NOT EXISTS delivery_street         VARCHAR(200),
  ADD COLUMN IF NOT EXISTS delivery_number         VARCHAR(20),
  ADD COLUMN IF NOT EXISTS delivery_complement     VARCHAR(100),
  ADD COLUMN IF NOT EXISTS delivery_neighborhood   VARCHAR(100),
  ADD COLUMN IF NOT EXISTS delivery_city           VARCHAR(100),
  ADD COLUMN IF NOT EXISTS delivery_reference      VARCHAR(200),
  ADD COLUMN IF NOT EXISTS delivery_lat            NUMERIC(9,6),
  ADD COLUMN IF NOT EXISTS delivery_lng            NUMERIC(9,6),
  ADD COLUMN IF NOT EXISTS delivery_geocode_source VARCHAR(30);
