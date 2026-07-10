-- V58: conta de anuncio da Meta (Facebook/Instagram Ads) conectada pelo tenant.
-- Fase 8.0 (Central de Trafego Pago, read-only): o restaurante cola um System User
-- Token gerado no proprio Business Manager; o backend valida via GET /me/adaccounts
-- e, se OK, salva a conta com o token CIFRADO.
--
-- Vive no banco do TENANT (db-per-tenant), entao NAO tem coluna de escopo: o banco
-- ja isola por restaurante. O token e cifrado em AES-256-GCM pelo MESMO mecanismo dos
-- tokens iFood/TOTP/GA4 (IfoodTokenCipher; chave em env IFOOD_ENCRYPTION_KEY, fora do
-- banco). Padrao do repo: *_enc BYTEA + *_iv BYTEA (IV aleatorio de 12 bytes por valor,
-- gravados/limpos juntos — nunca um sem o outro). O token nunca sai em claro nem e
-- devolvido em nenhum GET.
CREATE TABLE ad_account (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider              VARCHAR(20)  NOT NULL DEFAULT 'META',
    external_account_id   VARCHAR(50)  NOT NULL,   -- account_id numerico da Meta (sem o prefixo "act_")
    account_name          VARCHAR(200),
    currency              VARCHAR(10),
    timezone_name         VARCHAR(50),
    page_id               VARCHAR(50),
    page_name             VARCHAR(200),
    token_enc             BYTEA        NOT NULL,    -- System User Token cifrado (AES-256-GCM)
    token_iv              BYTEA        NOT NULL,    -- IV de 12 bytes emparelhado com token_enc
    token_type            VARCHAR(20)  NOT NULL DEFAULT 'SYSTEM_USER',
    status                VARCHAR(20)  NOT NULL DEFAULT 'CONNECTED',  -- CONNECTED | ERROR | DISCONNECTED
    last_error            TEXT,
    connected_by_user_id  UUID,                     -- usuario (banco de CONTROLE) que conectou; sem FK cross-db
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Uma linha por conta de anuncio (o upsert do reconnect atualiza a existente em vez
    -- de duplicar). db-per-tenant => a unicidade ja e naturalmente por restaurante.
    CONSTRAINT uq_ad_account_provider_external UNIQUE (provider, external_account_id)
);

COMMENT ON TABLE  ad_account IS 'Contas de anuncio (Meta) conectadas pelo tenant. Token cifrado; nunca devolvido em claro.';
COMMENT ON COLUMN ad_account.external_account_id IS 'account_id numerico da Meta (sem o prefixo act_).';
COMMENT ON COLUMN ad_account.token_enc IS 'System User Token cifrado (AES-256-GCM, mesma chave dos tokens iFood). NULL nunca.';
COMMENT ON COLUMN ad_account.token_iv IS 'IV de 12 bytes do AES-256-GCM. Emparelhado com token_enc.';
COMMENT ON COLUMN ad_account.status IS 'CONNECTED | ERROR | DISCONNECTED.';
