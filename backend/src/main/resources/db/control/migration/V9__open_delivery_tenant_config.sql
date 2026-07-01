-- Fase 5.5a — vinculo de um TENANT (company) a uma plataforma Open Delivery
-- (99Food / Rappi), no banco de CONTROLE. Mesmo modelo do V8 (iFood), mas com
-- auth mais simples: OAuth2 client_credentials (client_id + client_secret), sem
-- refresh token. O client_secret e o access_token ficam CIFRADOS (AES-256-GCM em
-- BYTEA com IV proprio por segredo) — cifra do Centuriao (chave fora do banco).
-- UNIQUE(company_id): 1 vinculo Open Delivery por company nesta fase.
CREATE TABLE open_delivery_tenant_config (
  id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  company_id            UUID         NOT NULL UNIQUE,
  platform              VARCHAR(15)  NOT NULL CHECK (platform IN ('NINETY_NINE','RAPPI')),
  base_url              VARCHAR(255) NOT NULL,
  client_id             VARCHAR(120) NOT NULL,
  client_secret_enc     BYTEA        NOT NULL,
  client_secret_iv      BYTEA        NOT NULL,
  key_version           INT          NOT NULL DEFAULT 1,
  access_token_enc      BYTEA,
  access_token_iv       BYTEA,
  token_expires_at      TIMESTAMPTZ,
  status                VARCHAR(12)  NOT NULL DEFAULT 'DISCONNECTED'
                        CHECK (status IN ('ACTIVE','DEGRADED','SUSPENDED','DISCONNECTED')),
  last_successful_poll  TIMESTAMPTZ,
  consecutive_failures  INT          NOT NULL DEFAULT 0,
  created_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_od_tenant_platform ON open_delivery_tenant_config(platform);
CREATE INDEX idx_od_tenant_status   ON open_delivery_tenant_config(status);
