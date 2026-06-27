-- MenuFlow TENANT — comandas (sessões de mesa) + FK no pedido (Flyway V12).
-- Uma sessão agrupa os pedidos de uma mesa entre abrir e fechar a conta.
-- Estados: OPEN -> BILLING -> CLOSED.
-- NOTE: never edit this file once applied — Flyway tracks it by checksum.

CREATE TABLE IF NOT EXISTS table_sessions (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    table_id           uuid NOT NULL REFERENCES restaurant_tables (id),
    status             varchar(10) NOT NULL DEFAULT 'OPEN',
    opened_at          timestamptz NOT NULL DEFAULT now(),
    opened_by_user_id  uuid,
    bill_requested_at  timestamptz,
    closed_at          timestamptz,
    closed_by_user_id  uuid,
    version            bigint NOT NULL DEFAULT 0,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);

-- Invariante: no máximo UMA sessão ativa (OPEN ou BILLING) por mesa. Sessões
-- CLOSED ficam fora do índice, então o histórico não conflita. Defesa em
-- profundidade junto da checagem em TableService.openSession.
CREATE UNIQUE INDEX IF NOT EXISTS uq_session_active_per_table
    ON table_sessions (table_id) WHERE status <> 'CLOSED';

CREATE INDEX IF NOT EXISTS idx_table_sessions_table ON table_sessions (table_id);

-- Vincula o pedido à comanda (nullable: pedidos de balcão/delivery não têm mesa).
ALTER TABLE orders ADD COLUMN IF NOT EXISTS table_session_id uuid REFERENCES table_sessions (id);
CREATE INDEX IF NOT EXISTS idx_orders_table_session ON orders (table_session_id);
