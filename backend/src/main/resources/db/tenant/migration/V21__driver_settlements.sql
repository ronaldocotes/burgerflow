-- MenuFlow TENANT — acerto financeiro de entregadores — Flyway V21.
-- Configuracao de remuneracao por entregador + acerto por periodo (congela ao
-- fechar). Tudo db-per-tenant (1 restaurante por banco). Dinheiro SEMPRE em
-- centavos (BIGINT). NOTE: never edit this file once applied — Flyway tracks by checksum.
--
-- DECISAO (grounded no codigo real): a remuneracao e do ENTREGADOR, que no
-- MenuFlow e a entidade delivery_drivers (banco do tenant) — orders.driver_id
-- referencia delivery_drivers.id (DeliveryService.assign: order.driverId = driver.id).
-- Por isso driver_id aqui referencia delivery_drivers, NAO users.id do banco de
-- controle (caso contrario o COUNT de entregas nunca casaria com orders.driver_id).

-- Configuracao de remuneracao por entregador (1 por entregador: UNIQUE).
CREATE TABLE driver_configs (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    driver_id           UUID         NOT NULL UNIQUE REFERENCES delivery_drivers(id),
    daily_rate_cents    BIGINT       NOT NULL DEFAULT 0,   -- diaria (R$ por dia trabalhado)
    per_delivery_cents  BIGINT       NOT NULL DEFAULT 0,   -- valor por entrega realizada
    per_km_cents        BIGINT       NOT NULL DEFAULT 0,   -- valor por km estimado (opcional)
    notes               TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_driver_config_nonneg
        CHECK (daily_rate_cents >= 0 AND per_delivery_cents >= 0 AND per_km_cents >= 0)
);
-- driver_id ja e UNIQUE (indice implicito); indice extra seria redundante.

-- Acerto de entregador por periodo. Imutavel apos fechamento (status CLOSED).
CREATE TABLE driver_settlements (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    driver_id            UUID         NOT NULL REFERENCES delivery_drivers(id),
    period_start         DATE         NOT NULL,
    period_end           DATE         NOT NULL,
    deliveries_count     INT          NOT NULL DEFAULT 0,
    working_days         INT          NOT NULL DEFAULT 0,
    daily_total_cents    BIGINT       NOT NULL DEFAULT 0,
    delivery_total_cents BIGINT       NOT NULL DEFAULT 0,
    km_total_cents       BIGINT       NOT NULL DEFAULT 0,
    -- Total bruto = soma das parcelas, calculado pelo banco (fonte de verdade SQL).
    gross_total_cents    BIGINT GENERATED ALWAYS AS
        (daily_total_cents + delivery_total_cents + km_total_cents) STORED,
    status               VARCHAR(16)  NOT NULL DEFAULT 'OPEN',  -- OPEN | CLOSED
    closed_by_user_id    UUID,
    closed_at            TIMESTAMPTZ,
    notes                TEXT,
    version              BIGINT       NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_settlement_status CHECK (status IN ('OPEN','CLOSED')),
    CONSTRAINT chk_settlement_period CHECK (period_end >= period_start),
    CONSTRAINT chk_settlement_nonneg CHECK (
        deliveries_count >= 0 AND working_days >= 0 AND
        daily_total_cents >= 0 AND delivery_total_cents >= 0 AND km_total_cents >= 0
    )
);
CREATE INDEX idx_settlement_driver ON driver_settlements (driver_id);
CREATE INDEX idx_settlement_period ON driver_settlements (period_start, period_end);
-- Invariante: no maximo 1 acerto OPEN por entregador (defesa em profundidade
-- junto da checagem existsByDriverIdAndStatus no servico).
CREATE UNIQUE INDEX uq_settlement_open ON driver_settlements (driver_id) WHERE status = 'OPEN';
