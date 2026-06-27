-- Complementos do cardápio: grupos de opções por produto (ex.: "Ponto da carne",
-- "Adicionais") e suas opções. Dinheiro em centavos. FK ON DELETE CASCADE para
-- limpar opções/grupos quando o produto é removido fisicamente.

CREATE TABLE product_option_groups (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    min_select INT NOT NULL DEFAULT 0,
    max_select INT NOT NULL DEFAULT 1,
    active BOOLEAN NOT NULL DEFAULT true,
    display_order INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_option_groups_product ON product_option_groups (product_id);

CREATE TABLE product_options (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id UUID NOT NULL REFERENCES product_option_groups(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    price_cents BIGINT NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT true,
    display_order INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_options_group ON product_options (group_id);
