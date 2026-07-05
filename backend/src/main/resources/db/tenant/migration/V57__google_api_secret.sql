-- V57: api_secret do Google Measurement Protocol (GA4) — fix Fase 3.7 (sGTM).
-- Bug: ConversionService montava "...&api_secret=" (vazio) porque nao existia
-- coluna para o segredo; o GA4 responde 204 mas DESCARTA o evento em silencio.
--
-- O segredo e cifrado em AES-256-GCM com a MESMA chave/mecanismo dos tokens iFood
-- e do TOTP (IfoodTokenCipher; chave em env IFOOD_ENCRYPTION_KEY, fora do banco).
-- Padrao do repo: *_enc BYTEA + *_iv BYTEA (IV aleatorio de 12 bytes por valor,
-- ambos gravados/limpos juntos — nunca um sem o outro).
ALTER TABLE tenant_config
  ADD COLUMN IF NOT EXISTS google_api_secret_enc BYTEA,
  ADD COLUMN IF NOT EXISTS google_api_secret_iv  BYTEA;

COMMENT ON COLUMN tenant_config.google_api_secret_enc IS
  'api_secret do GA4 Measurement Protocol cifrado (AES-256-GCM). NULL = nao configurado. Nunca em claro; nunca devolvido no GET /config.';
COMMENT ON COLUMN tenant_config.google_api_secret_iv IS
  'IV de 12 bytes do AES-256-GCM do api_secret. Emparelhado com google_api_secret_enc.';
