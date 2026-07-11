-- MenuFlow TENANT — remuneracao de motoboy + acerto por periodo (issue #3) — Flyway V62.
-- Complementa a V21 (driver_configs + driver_settlements). Tudo db-per-tenant
-- (1 restaurante por banco). Dinheiro SEMPRE em centavos (BIGINT).
-- NOTE: never edit this file once applied — Flyway tracks by checksum.
--
-- Escopo desta migracao:
--  1) orders.delivery_distance_meters: persiste a distancia rodoviaria calculada
--     na precificacao do frete (antes descartada em OrderService), para alimentar
--     o eixo por-km do acerto da FROTA. Aditivo e NULLABLE (modo por-zona/linha
--     reta pode nao ter essa distancia -> fica NULL, sem quebrar o pedido).
--  2) driver_settlements ganha o repasse do FREELANCER (payout_total_cents), o
--     snapshot do tipo de remuneracao usada (settlement_type) e a distancia que
--     originou o eixo km (km_total_meters).
--  3) gross_total_cents e COLUNA GERADA: precisa ser DROPADA e RECRIADA para somar
--     tambem payout_total_cents. Com DEFAULT 0, os acertos CLOSED antigos recomputam
--     para o MESMO valor (0 de payout) -> historico intacto.

-- (1) Distancia da entrega no pedido (alimenta o km da frota).
ALTER TABLE orders ADD COLUMN delivery_distance_meters BIGINT NULL;

-- (2) Novos campos do acerto.
ALTER TABLE driver_settlements ADD COLUMN payout_total_cents BIGINT      NOT NULL DEFAULT 0;
ALTER TABLE driver_settlements ADD COLUMN settlement_type     VARCHAR(12) NOT NULL DEFAULT 'FROTA';
ALTER TABLE driver_settlements ADD COLUMN km_total_meters     BIGINT      NULL;

-- (3) gross_total_cents e GENERATED: nao da para ALTER da expressao; DROP + ADD.
-- A nova expressao inclui payout_total_cents. Ordem importa: payout ja existe (passo 2).
ALTER TABLE driver_settlements DROP COLUMN gross_total_cents;
ALTER TABLE driver_settlements ADD COLUMN gross_total_cents BIGINT GENERATED ALWAYS AS
    (daily_total_cents + delivery_total_cents + km_total_cents + payout_total_cents) STORED;

-- Bounds (G6): sem valores negativos; snapshot de tipo restrito ao dominio.
ALTER TABLE driver_settlements
    ADD CONSTRAINT chk_settlement_payout_nonneg CHECK (payout_total_cents >= 0);
ALTER TABLE driver_settlements
    ADD CONSTRAINT chk_settlement_type CHECK (settlement_type IN ('FROTA','FREELANCER'));
ALTER TABLE driver_settlements
    ADD CONSTRAINT chk_settlement_km_meters CHECK (km_total_meters IS NULL OR km_total_meters >= 0);
ALTER TABLE orders
    ADD CONSTRAINT chk_orders_delivery_distance CHECK (delivery_distance_meters IS NULL OR delivery_distance_meters >= 0);
