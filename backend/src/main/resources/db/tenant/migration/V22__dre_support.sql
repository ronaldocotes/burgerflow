-- MenuFlow TENANT — suporte ao DRE automático (Fase 3.1) — Flyway V22.
-- Tudo db-per-tenant (1 restaurante por banco). Dinheiro SEMPRE em centavos
-- (BIGINT). NOTE: never edit this file once applied — Flyway tracks by checksum.
--
-- Acrescenta:
--  (1) snapshots de custo/taxa no pedido (cogs/marketplace/cartão), gravados no
--      momento da venda para o DRE ser determinístico mesmo que preços/fichas
--      mudem depois;
--  (2) o canal de venda do pedido (sales_channel) para o recorte por canal;
--  (3) a tabela de despesas operacionais lançadas manualmente;
--  (4) as alíquotas (%) de marketplace, cartão e impostos no tenant_config.

-- (1) e (2) Snapshots de custo/taxa + canal no pedido.
ALTER TABLE orders ADD COLUMN cogs_cents            BIGINT      NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN marketplace_fee_cents BIGINT      NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN card_fee_cents        BIGINT      NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN sales_channel         VARCHAR(20) NOT NULL DEFAULT 'COUNTER';

ALTER TABLE orders ADD CONSTRAINT chk_orders_dre_nonneg
    CHECK (cogs_cents >= 0 AND marketplace_fee_cents >= 0 AND card_fee_cents >= 0);

-- Backfill do canal nos pedidos existentes a partir do order_type (DINE_IN/
-- DELIVERY mapeiam direto; TAKEAWAY vira COUNTER). Pedidos antigos ficam com
-- cogs/taxas = 0 (não há como reconstruir o custo histórico retroativamente).
UPDATE orders SET sales_channel = CASE
    WHEN order_type = 'DELIVERY' THEN 'DELIVERY'
    WHEN order_type = 'DINE_IN'  THEN 'DINE_IN'
    ELSE 'COUNTER'
END;

-- (3) Despesas operacionais (aluguel, energia, salários, marketing, etc.),
-- lançadas manualmente pela gestão. Entram no DRE pela expense_date.
CREATE TABLE operating_expenses (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    description  VARCHAR(200) NOT NULL,
    amount_cents BIGINT       NOT NULL,
    category     VARCHAR(50)  NOT NULL,   -- RENT | UTILITIES | PAYROLL | MARKETING | OTHER
    expense_date DATE         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_operating_expense_amount CHECK (amount_cents >= 0)
);
CREATE INDEX idx_operating_expenses_date ON operating_expenses (expense_date);

-- (4) Alíquotas (%) do tenant. NUMERIC(5,2): 0,00 a 999,99 (cap de negócio 100
-- é validado na API). IF NOT EXISTS por robustez (tenant_config já existe desde
-- a V13). DEFAULT 0 preenche os tenants existentes sem quebrar.
ALTER TABLE tenant_config ADD COLUMN IF NOT EXISTS marketplace_fee_pct NUMERIC(5,2) NOT NULL DEFAULT 0;
ALTER TABLE tenant_config ADD COLUMN IF NOT EXISTS card_fee_pct        NUMERIC(5,2) NOT NULL DEFAULT 0;
ALTER TABLE tenant_config ADD COLUMN IF NOT EXISTS tax_pct             NUMERIC(5,2) NOT NULL DEFAULT 0;
