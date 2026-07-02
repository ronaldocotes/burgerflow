-- Fase CONFIG-A / issue #9: tempo minimo/maximo estimado por modalidade.
-- Vira a "promessa de prazo" exibida no cardapio publico (ex.: "Entrega 30-60 min").
-- Em minutos. Um par (min,max) por modalidade: delivery, retirada (pickup) e
-- consumo local (dine-in). NOT NULL com defaults sensatos para o restaurante ja
-- ter uma promessa razoavel sem configurar. NOTE: never edit once applied.
ALTER TABLE tenant_config
  ADD COLUMN IF NOT EXISTS delivery_time_min_minutes  int NOT NULL DEFAULT 30,
  ADD COLUMN IF NOT EXISTS delivery_time_max_minutes  int NOT NULL DEFAULT 60,
  ADD COLUMN IF NOT EXISTS pickup_time_min_minutes    int NOT NULL DEFAULT 15,
  ADD COLUMN IF NOT EXISTS pickup_time_max_minutes    int NOT NULL DEFAULT 30,
  ADD COLUMN IF NOT EXISTS dinein_time_min_minutes    int NOT NULL DEFAULT 10,
  ADD COLUMN IF NOT EXISTS dinein_time_max_minutes    int NOT NULL DEFAULT 20;
