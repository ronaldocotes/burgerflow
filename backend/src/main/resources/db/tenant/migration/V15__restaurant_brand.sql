-- Dados de marca/vitrine do restaurante para o cardapio publico.
-- Todos nullable: restaurante recem-criado pode nao ter preenchido nada.
ALTER TABLE tenant_config
  ADD COLUMN restaurant_name   VARCHAR(100),
  ADD COLUMN logo_url          VARCHAR(500),
  ADD COLUMN cover_url         VARCHAR(500),
  ADD COLUMN address           VARCHAR(200),
  ADD COLUMN opening_hours     VARCHAR(200),
  ADD COLUMN merchant_city     VARCHAR(50);
