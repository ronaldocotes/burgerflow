-- Fase CONFIG-B / issue #12: tema do cardapio publico.
-- Cor principal (aplicada como CSS var no /cardapio) + toggles para exibir
-- precos/descricoes/fotos. Colunas aditivas na linha unica de tenant_config
-- (mesmo padrao de "config cresce em colunas"). A cor e opcional (null =
-- frontend usa a cor default); os toggles nascem LIGADOS (default true) para
-- nao mudar o comportamento atual do cardapio. NOTE: never edit once applied.
ALTER TABLE tenant_config
  ADD COLUMN IF NOT EXISTS theme_primary_color     VARCHAR(7),   -- hex "#RRGGBB"; null = default
  ADD COLUMN IF NOT EXISTS theme_show_prices       BOOLEAN NOT NULL DEFAULT true,
  ADD COLUMN IF NOT EXISTS theme_show_descriptions BOOLEAN NOT NULL DEFAULT true,
  ADD COLUMN IF NOT EXISTS theme_show_photos       BOOLEAN NOT NULL DEFAULT true;
