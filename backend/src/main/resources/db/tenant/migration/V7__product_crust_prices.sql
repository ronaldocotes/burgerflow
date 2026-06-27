-- Preço da borda (CrustType) POR produto. A borda escolhida no item de pizza soma
-- seu preço ao preço unitário. crust_type guarda o NOME do enum CrustType (STRING).
-- UNIQUE(product_id, crust_type): no máximo um preço por borda por produto.
-- Borda sem registro = preço 0 (resolvido no OrderService).
CREATE TABLE product_crust_prices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    crust_type VARCHAR(30) NOT NULL,
    price_cents BIGINT NOT NULL DEFAULT 0,
    UNIQUE (product_id, crust_type)
);

CREATE INDEX idx_product_crust_prices_product ON product_crust_prices (product_id);
