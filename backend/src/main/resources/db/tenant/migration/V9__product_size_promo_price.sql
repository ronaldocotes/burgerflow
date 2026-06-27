-- Preço promocional por TAMANHO de pizza (centavos, opcional). A janela de validade
-- é a do PRODUTO (products.promo_starts_at/ends_at). Quando o produto está em promo e
-- o tamanho tem promo_price_cents, o preço efetivo do tamanho é o promocional.
ALTER TABLE product_sizes ADD COLUMN promo_price_cents BIGINT;
