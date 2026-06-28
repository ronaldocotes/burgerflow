-- MenuFlow TENANT — cobrancas PIX via Asaas (Fase 2.3, Flyway V18).
-- Vive no banco do TENANT (db-per-tenant): cada PaymentIntent e cada evento de
-- webhook ja pertencem, por construcao fisica, a um unico restaurante. Dinheiro
-- SEMPRE em centavos (BIGINT). NOTE: never edit this file once applied — Flyway
-- tracks by checksum.

CREATE TABLE payment_intents (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id            uuid         NOT NULL REFERENCES orders(id),
    asaas_payment_id    varchar(64)  UNIQUE,
    status              varchar(16)  NOT NULL DEFAULT 'PENDING',
    amount_cents        bigint       NOT NULL,
    pix_qr_image        text,            -- base64 da imagem do QR
    pix_copy_paste      text,            -- payload copia-e-cola
    paid_at             timestamptz,
    expires_at          timestamptz,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT chk_pi_status CHECK (status IN ('PENDING','PAID','FAILED','EXPIRED'))
);
CREATE INDEX idx_pi_order  ON payment_intents (order_id);
CREATE INDEX idx_pi_asaas  ON payment_intents (asaas_payment_id);
CREATE INDEX idx_pi_status ON payment_intents (status, expires_at);

-- Deduplicacao de webhooks (append-only). O id do evento Asaas e unico; reentrega
-- do MESMO evento e ignorada (idempotencia). UNIQUE ja cria o indice; o indice
-- nomeado extra do prompt seria redundante, entao mantemos so a constraint.
CREATE TABLE webhook_events (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id    varchar(64) UNIQUE NOT NULL,   -- id do evento Asaas
    received_at timestamptz NOT NULL DEFAULT now()
);
