-- MenuFlow TENANT — módulo Mesas e Comandas (Flyway V11).
-- Mesas físicas do salão. Dinheiro não se aplica aqui; valores ficam nos pedidos.
-- NOTE: never edit this file once applied — Flyway tracks it by checksum.

CREATE TABLE IF NOT EXISTS restaurant_tables (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    label       varchar(40) NOT NULL,
    seats       smallint NOT NULL DEFAULT 4,
    sort_order  int NOT NULL DEFAULT 0,
    active      boolean NOT NULL DEFAULT true,
    version     bigint NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

-- Rótulo único SOMENTE entre mesas ativas (uma mesa desativada libera o rótulo
-- para reuso). Índice parcial em vez de constraint UNIQUE cheia.
CREATE UNIQUE INDEX IF NOT EXISTS uq_table_label_active
    ON restaurant_tables (label) WHERE active = true;
