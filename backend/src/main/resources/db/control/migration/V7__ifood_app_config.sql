-- Fase 5.1a — credencial da APLICACAO iFood (nivel plataforma, banco de CONTROLE).
-- Uma linha por app registrado no iFood (PRIMARY e, opcionalmente, BACKUP para
-- failover). O client_secret e o app_token ficam CIFRADOS (AES-256-GCM) em BYTEA
-- com IV proprio por segredo; a chave/versao de cifra vive fora do banco (Centuriao).
CREATE TABLE ifood_app_config (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  role                VARCHAR(10)  NOT NULL CHECK (role IN ('PRIMARY','BACKUP')),
  client_id           VARCHAR(120) NOT NULL,
  client_secret_enc   BYTEA        NOT NULL,
  client_secret_iv    BYTEA        NOT NULL,
  key_version         INT          NOT NULL DEFAULT 1,
  cnpj                VARCHAR(14)  NOT NULL,
  app_token_enc       BYTEA,
  app_token_iv        BYTEA,
  token_expires_at    TIMESTAMPTZ,
  active              BOOLEAN      NOT NULL DEFAULT FALSE,
  created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
