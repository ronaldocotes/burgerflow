-- Fase CONFIG-B / issue #13: pop-up de entrada com produtos em destaque.
-- Exibido ao abrir o cardapio publico via QR (upsell na porta de entrada).
-- O toggle ativo/inativo e o titulo ficam na linha de tenant_config (config
-- cresce em colunas). Os produtos em destaque (ate 3 -- guard-rail no service)
-- ficam nesta tabela filha, ordenada. FK para products com ON DELETE CASCADE:
-- se o produto for removido de vez, o destaque some junto (produtos usam
-- soft-delete via active=false, entao na pratica o cascade quase nunca dispara;
-- o service filtra inativos na leitura). NOTE: never edit once applied.
ALTER TABLE tenant_config
  ADD COLUMN IF NOT EXISTS entry_popup_enabled BOOLEAN NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS entry_popup_title   VARCHAR(120);

CREATE TABLE IF NOT EXISTS entry_popup_products (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id uuid NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    sort_order int NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    -- Um produto nao pode aparecer duas vezes no pop-up.
    CONSTRAINT uq_entry_popup_product UNIQUE (product_id)
);
