-- V61: zonas de entrega por RAIO (aneis) com preco e ETA (issue #2).
-- Decisao do dono D-1: o anel e resolvido por RAIO EM LINHA RETA (Haversine) a partir
-- do restaurante (tenant_config.restaurant_lat/lng). O circulo do mapa e o que COBRA —
-- NAO distancia rodoviaria. Cada anel cobre do raio anterior ate seu max_radius_km; a
-- zona aplicavel e a de MENOR max_radius_km cujo raio >= distancia do ponto de entrega.
--
-- Vive no banco do TENANT (db-per-tenant): sem coluna de escopo, o banco ja isola por
-- restaurante. Fase de DINHEIRO (frete cobrado do cliente): valor SEMPRE em centavos
-- (BIGINT), nunca float. A tarifa passa a ser SERVER-DERIVED quando ha zonas ativas —
-- o deliveryFeeCents do cliente e ignorado (fecha a fraude de frete R$0).
CREATE TABLE delivery_zone (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name              VARCHAR(100),                       -- rotulo opcional do anel (ex.: "Centro")
    max_radius_km     DOUBLE PRECISION NOT NULL,          -- raio externo do anel (km, linha reta)
    fee_cents         BIGINT       NOT NULL,              -- frete cobrado do cliente nesta zona (centavos)
    eta_min_minutes   INT          NOT NULL,              -- promessa de prazo minimo desta zona
    eta_max_minutes   INT          NOT NULL,              -- promessa de prazo maximo desta zona
    is_free           BOOLEAN      NOT NULL DEFAULT false,-- anel de frete gratis (fee ignorado)
    display_order     INT          NOT NULL DEFAULT 0,    -- ordem dos aneis (do menor raio ao maior)
    active            BOOLEAN      NOT NULL DEFAULT true,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Lookup do resolver: zonas ativas ordenadas pelo raio crescente (pega a primeira que cobre).
CREATE INDEX idx_delivery_zone_active_radius ON delivery_zone (active, max_radius_km);

COMMENT ON TABLE  delivery_zone IS 'Aneis de cobertura de entrega por raio (Haversine) com preco e ETA. Zona aplicavel = menor max_radius_km >= distancia. Fora de todos os aneis = fora da area de entrega.';
COMMENT ON COLUMN delivery_zone.max_radius_km IS 'Raio externo do anel em km (linha reta a partir de tenant_config.restaurant_lat/lng).';
COMMENT ON COLUMN delivery_zone.fee_cents IS 'Frete cobrado do cliente nesta zona (centavos, BIGINT, nunca float). Ignorado quando is_free.';
COMMENT ON COLUMN delivery_zone.is_free IS 'Anel de frete gratis: quando true, o frete e 0 independentemente de fee_cents.';

-- "Frete gratis acima de X": limiar GLOBAL (nao por anel), entao vive no tenant_config
-- (linha unica do restaurante) em vez de repetir por zona. NULL = sem frete gratis por
-- valor de pedido. Comparado contra o SUBTOTAL do pedido no resolver de zona.
ALTER TABLE tenant_config
    ADD COLUMN free_delivery_min_order_cents BIGINT;

COMMENT ON COLUMN tenant_config.free_delivery_min_order_cents IS 'Frete gratis quando o subtotal do pedido >= este valor (centavos). NULL = desabilitado. Global (nao por anel); resolvido junto das delivery_zone.';
