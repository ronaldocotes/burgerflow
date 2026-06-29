-- MenuFlow TENANT — Cupons & Descontos — Flyway V23.
-- Tudo db-per-tenant (1 restaurante por banco), sem coluna de escopo. Dinheiro
-- SEMPRE em centavos (BIGINT). NOTE: never edit this file once applied — Flyway
-- tracks by checksum.

-- Cupom de desconto. O code e a chave natural (UNIQUE), guardado SEMPRE em
-- maiusculas+trim pelo servico (lookup case-insensitive). discount_value:
--   FIXED   -> valor do desconto em centavos;
--   PERCENT -> percentual x 100 (ex.: 1500 = 15,00%).
CREATE TABLE coupons (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code                  VARCHAR(50)  NOT NULL,
    description           VARCHAR(200),
    discount_type         VARCHAR(20)  NOT NULL,            -- FIXED | PERCENT
    discount_value        BIGINT       NOT NULL,            -- centavos (FIXED) ou %x100 (PERCENT)
    min_order_cents       BIGINT       NOT NULL DEFAULT 0,  -- pedido minimo para usar
    max_uses              INT,                              -- NULL = ilimitado (global)
    max_uses_per_customer INT          NOT NULL DEFAULT 1,  -- por telefone
    valid_from            TIMESTAMPTZ  NOT NULL,
    valid_until           TIMESTAMPTZ  NOT NULL,
    active                BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_coupon_code UNIQUE (code),
    CONSTRAINT chk_coupon_discount_type CHECK (discount_type IN ('FIXED', 'PERCENT')),
    CONSTRAINT chk_coupon_discount_value CHECK (discount_value > 0),
    CONSTRAINT chk_coupon_min_order_nonneg CHECK (min_order_cents >= 0),
    CONSTRAINT chk_coupon_max_uses_pos CHECK (max_uses IS NULL OR max_uses > 0),
    CONSTRAINT chk_coupon_max_per_customer_pos CHECK (max_uses_per_customer > 0),
    CONSTRAINT chk_coupon_window CHECK (valid_until > valid_from)
);

-- Redencao (uso) de um cupom num pedido. order_id referencia o pedido; a contagem
-- por cupom (e por telefone) e a fonte de verdade do maxUses/maxUsesPerCustomer.
CREATE TABLE coupon_redemptions (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    coupon_id              UUID         NOT NULL REFERENCES coupons(id),
    order_id               UUID         NOT NULL REFERENCES orders(id),
    customer_phone         VARCHAR(20),
    discount_applied_cents BIGINT       NOT NULL,
    redeemed_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_redemption_discount_nonneg CHECK (discount_applied_cents >= 0)
);
CREATE INDEX idx_redemptions_coupon ON coupon_redemptions(coupon_id);
CREATE INDEX idx_redemptions_phone ON coupon_redemptions(customer_phone);

-- Snapshot do cupom no pedido (alem do desconto, que ja entra em discount_cents).
-- coupon_discount_cents preserva o valor abatido pelo cupom mesmo que o cupom mude
-- depois (determinismo do pedido). coupon_code e snapshot do codigo usado.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS coupon_id UUID REFERENCES coupons(id);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS coupon_code VARCHAR(50);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS coupon_discount_cents BIGINT NOT NULL DEFAULT 0;
