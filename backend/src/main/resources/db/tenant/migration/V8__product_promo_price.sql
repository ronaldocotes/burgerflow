-- Preço promocional do produto (centavos, opcional) com janela de validade.
-- Quando promo_price_cents != null e "agora" está dentro da janela (limites nulos =
-- sem limite), o preço efetivo do produto passa a ser o promocional.
ALTER TABLE products
    ADD COLUMN promo_price_cents BIGINT,
    ADD COLUMN promo_starts_at TIMESTAMPTZ,
    ADD COLUMN promo_ends_at TIMESTAMPTZ;
