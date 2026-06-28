-- MenuFlow TENANT — turno de caixa (CashSession) — Flyway V16.
-- Reconcilia o dinheiro do balcão: abertura -> vendas em dinheiro + reforços
-- - sangrias = esperado; comparado com o contado no fechamento. Tudo em
-- db-per-tenant (1 restaurante por banco). Dinheiro SEMPRE em centavos (BIGINT).
-- NOTE: never edit this file once applied — Flyway tracks by checksum.

CREATE TABLE cash_sessions (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    status                 VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    opened_by_user_id      UUID         NOT NULL,
    opened_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    opening_amount_cents   BIGINT       NOT NULL DEFAULT 0,
    closed_by_user_id      UUID,
    closed_at              TIMESTAMPTZ,
    closing_counted_cents  BIGINT,
    closing_expected_cents BIGINT,
    -- Diferença persistida pelo banco (fonte de verdade p/ relatório SQL):
    -- contado - esperado. Negativo = falta no caixa; positivo = sobra.
    difference_cents       BIGINT GENERATED ALWAYS AS (closing_counted_cents - closing_expected_cents) STORED,
    notes                  TEXT,
    version                BIGINT       NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_cash_session_status CHECK (status IN ('OPEN','CLOSED')),
    CONSTRAINT chk_cash_opening_nonneg CHECK (opening_amount_cents >= 0)
);

-- Invariante central: no máximo UMA sessão aberta por restaurante (banco).
-- Índice parcial garante a unicidade só entre as abertas (defesa em profundidade
-- além da checagem existsByStatus no serviço — fecha a corrida de dois opens).
CREATE UNIQUE INDEX uq_cash_sessions_single_open ON cash_sessions (status) WHERE status = 'OPEN';
CREATE INDEX idx_cash_sessions_opened_at ON cash_sessions (opened_at DESC);

-- Movimentos manuais do caixa: sangria (WITHDRAWAL, retira dinheiro) e
-- reforço (DEPOSIT, adiciona dinheiro). Sempre valor positivo; o sinal vem do tipo.
CREATE TABLE cash_session_entries (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id         UUID         NOT NULL REFERENCES cash_sessions(id) ON DELETE CASCADE,
    type               VARCHAR(16)  NOT NULL,
    amount_cents       BIGINT       NOT NULL,
    reason             TEXT,
    created_by_user_id UUID         NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_entry_type   CHECK (type IN ('WITHDRAWAL','DEPOSIT')),
    CONSTRAINT chk_entry_amount CHECK (amount_cents > 0)
);
CREATE INDEX idx_cash_entries_session ON cash_session_entries (session_id);

-- Vínculo do pedido ao turno de caixa: venda em dinheiro do PDV entra no
-- esperado do turno aberto no momento da venda. Nullable: pedido sem caixa
-- (cardápio público, cartão, pix) não referencia turno.
ALTER TABLE orders ADD COLUMN cash_session_id UUID;
CREATE INDEX idx_orders_cash_session ON orders (cash_session_id);
