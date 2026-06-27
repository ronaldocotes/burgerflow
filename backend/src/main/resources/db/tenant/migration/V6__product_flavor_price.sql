-- Preço do sabor de pizza (centavos). O preço do item de pizza soma a MÉDIA dos
-- preços dos sabores escolhidos (1 sabor = ele mesmo; 2 sabores meia/meia = média).
-- Default 0 mantém compatível com sabores já cadastrados (sabor sem custo extra).
ALTER TABLE product_flavors ADD COLUMN price_cents BIGINT NOT NULL DEFAULT 0;
