-- Fase CONFIG-A / issue #7: endereco estruturado da loja (busca por CEP + mapa).
-- O tenant_config ja tinha `address` (texto livre), `merchant_city` e o pin
-- (restaurant_lat/restaurant_lng, criados na V39). Aqui adicionamos os campos
-- estruturados que a busca por CEP (ViaCEP, no frontend) preenche. Todos nullable:
-- um restaurante recem-criado ainda nao tem endereco. NOTE: never edit once applied.
ALTER TABLE tenant_config
  ADD COLUMN IF NOT EXISTS postal_code         VARCHAR(9),    -- CEP "00000-000"
  ADD COLUMN IF NOT EXISTS street              VARCHAR(200),  -- logradouro
  ADD COLUMN IF NOT EXISTS street_number       VARCHAR(20),   -- numero (texto: "S/N", "123A")
  ADD COLUMN IF NOT EXISTS address_complement  VARCHAR(100),  -- complemento
  ADD COLUMN IF NOT EXISTS neighborhood        VARCHAR(100),  -- bairro
  ADD COLUMN IF NOT EXISTS state_uf            VARCHAR(2);    -- UF (SP, RJ, ...)
