-- Fase 1 do Gerenciador de Chaves de API da Plataforma (nivel plataforma, banco de
-- CONTROLE; SEM escopo de tenant). Guarda a chave de provedores externos (MVP:
-- GOOGLE_MAPS, usada em distancia/geocode) CIFRADA (AES-256-GCM) em BYTEA com IV
-- proprio; a chave de cifra vive fora do banco (IFOOD_ENCRYPTION_KEY / Centuriao).
-- O texto claro nunca e persistido nem logado.
--
-- NAO confundir com tenant_config.google_api_secret (V57 = GA4 per-tenant): outra
-- feature, outro escopo.
CREATE TABLE platform_api_key (
  id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  provider     VARCHAR(40)  NOT NULL,   -- enum logico; MVP: 'GOOGLE_MAPS'
  value_enc    BYTEA        NOT NULL,   -- valor cifrado (AES-256-GCM)
  value_iv     BYTEA        NOT NULL,   -- IV de 12 bytes proprio deste segredo
  key_version  INT          NOT NULL DEFAULT 1,
  active       BOOLEAN      NOT NULL DEFAULT TRUE,
  updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_by   UUID                     -- ator (control users); NULL = seed/sistema
);

-- No maximo UMA chave ATIVA por provedor. Indice PARCIAL: chaves inativas (historico
-- de rotacao) nao contam para a unicidade, entao pode haver varias com active=false.
CREATE UNIQUE INDEX ux_platform_api_key_provider_active
  ON platform_api_key (provider)
  WHERE active;
