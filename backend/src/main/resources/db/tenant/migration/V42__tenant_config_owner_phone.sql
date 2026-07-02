-- Fase B2: telefone do DONO para escalacao do despacho (quando ninguem aceita a corrida).
-- Nullable: sem telefone, a escalacao apenas loga e segue (fail-open). Distinto do
-- waha_primary_phone (numero da SESSAO que ENVIA), que nao e necessariamente onde o dono
-- quer receber os alertas.
ALTER TABLE tenant_config
  ADD COLUMN IF NOT EXISTS owner_phone VARCHAR(20);
