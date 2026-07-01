-- Fase 5.1a — vinculo de um TENANT (company) a um merchant iFood, no banco de
-- CONTROLE. Guarda os tokens OAuth do merchant (cifrados AES-256-GCM em BYTEA com
-- IV proprio) e o estado operacional da integracao (status/falhas/ultimo poll).
-- app_id aponta para o app da plataforma (PRIMARY/BACKUP) usado por este merchant.
-- UNIQUE(merchant_id) e UNIQUE(company_id): 1 merchant iFood <-> 1 company.
CREATE TABLE ifood_tenant_config (
  id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  company_id            UUID         NOT NULL,
  app_id                UUID         NOT NULL REFERENCES ifood_app_config(id),
  merchant_id           VARCHAR(64)  NOT NULL,
  access_token_enc      BYTEA,
  access_token_iv       BYTEA,
  refresh_token_enc     BYTEA,
  refresh_token_iv      BYTEA,
  token_expires_at      TIMESTAMPTZ,
  backup_authorized     BOOLEAN      NOT NULL DEFAULT FALSE,
  status                VARCHAR(12)  NOT NULL DEFAULT 'DISCONNECTED'
                        CHECK (status IN ('ACTIVE','DEGRADED','SUSPENDED','DISCONNECTED')),
  last_successful_poll  TIMESTAMPTZ,
  consecutive_failures  INT          NOT NULL DEFAULT 0,
  created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
  UNIQUE (merchant_id),
  UNIQUE (company_id)
);
CREATE INDEX idx_ifood_tenant_status ON ifood_tenant_config(status);
