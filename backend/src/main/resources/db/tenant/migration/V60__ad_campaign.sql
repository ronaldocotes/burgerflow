-- V60: campanha de anuncio propria da Meta (Fase 8.2 — criar/pausar/ativar campanha).
-- Esta e a fase que GASTA DINHEIRO REAL: cria campanha/adset/criativo/ad de verdade na
-- conta da Meta do restaurante. Por seguranca a campanha NASCE PAUSED (status=PAUSED,
-- effective_status espelhando a Meta); ativar e um endpoint separado e auditado que
-- REVALIDA o teto de verba.
--
-- Vive no banco do TENANT (db-per-tenant): sem coluna de escopo, o banco ja isola por
-- restaurante. Dinheiro SEMPRE em centavos (BIGINT) na moeda DA CONTA (ad_account.currency);
-- nunca float.
--
-- Idempotencia (anti-duplicacao de gasto): criar campanha e uma saga de 4 escritas externas
-- (campaign -> adset -> creative -> ad). O cliente manda um Idempotency-Key; a UNIQUE
-- (ad_account_id, idempotency_key) impede que um retry/double-click crie duas campanhas do
-- MESMO request. Um retry com a mesma chave devolve a campanha existente.
--
-- FK ad_account_id ON DELETE RESTRICT (DE PROPOSITO): desconectar a conta NAO pode apagar
-- silenciosamente uma campanha que ainda pode estar rodando/gastando na Meta. O
-- AdAccountService bloqueia o disconnect quando ha campanha ATIVA e limpa apenas as locais
-- nao-ativas (nunca mexe na Meta no disconnect).
CREATE TABLE ad_campaign (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ad_account_id         UUID        NOT NULL REFERENCES ad_account(id) ON DELETE RESTRICT,
    external_campaign_id  VARCHAR(50),                 -- id da campanha na Meta; NULL ate criar la
    external_adset_id     VARCHAR(50),                 -- id do adset na Meta
    external_ad_id        VARCHAR(50),                 -- id do ad na Meta
    name                  VARCHAR(200) NOT NULL,
    objective             VARCHAR(40)  NOT NULL DEFAULT 'OUTCOME_TRAFFIC',
    -- status LOCAL do usuario: DRAFT (reservado durante a saga) | PAUSED (criada, nasce assim)
    -- | ACTIVE (ativada explicitamente) | ARCHIVED. VARCHAR sem CHECK: novos estados sem migration.
    status                VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    effective_status      VARCHAR(40),                 -- espelho do effective_status da Meta (nullable)
    daily_budget_cents    BIGINT       NOT NULL,       -- verba diaria, centavos na moeda da conta
    geo_lat               DOUBLE PRECISION,            -- centro do raio de segmentacao
    geo_lng               DOUBLE PRECISION,
    radius_km             INT,                         -- raio em km [1..80] (limite da Meta)
    tracking_link_id      UUID,                        -- link de rastreio opcional (sem FK: decoupled)
    idempotency_key       VARCHAR(120) NOT NULL,       -- chave anti-duplicacao do request de criacao
    created_by_user_id    UUID,                        -- usuario (banco de CONTROLE) que criou; sem FK cross-db
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Nucleo da idempotencia: um Idempotency-Key vale por conta. Retry com a mesma chave
    -- NAO cria segunda campanha (o service devolve a existente).
    CONSTRAINT uq_ad_campaign_account_idem UNIQUE (ad_account_id, idempotency_key)
);

CREATE INDEX idx_ad_campaign_account ON ad_campaign (ad_account_id);

COMMENT ON TABLE  ad_campaign IS 'Campanhas de anuncio (Meta) criadas pelo tenant. Nascem PAUSED; ativar revalida o teto de verba. Idempotencia por (conta, idempotency_key).';
COMMENT ON COLUMN ad_campaign.status IS 'Status LOCAL: DRAFT | PAUSED | ACTIVE | ARCHIVED.';
COMMENT ON COLUMN ad_campaign.effective_status IS 'Espelho do effective_status da Meta (ex.: PENDING_REVIEW, ACTIVE, PAUSED). Nullable.';
COMMENT ON COLUMN ad_campaign.daily_budget_cents IS 'Verba diaria em centavos na moeda da conta (BIGINT, nunca float). Piso R$10,00; teto = entitlement do tenant.';
COMMENT ON COLUMN ad_campaign.idempotency_key IS 'Chave idempotente do request de criacao. UNIQUE por conta impede campanha duplicada em retry/double-click.';

-- Criativo do anuncio (texto + imagem do catalogo). CASCADE: apagar a campanha local apaga
-- o criativo junto. Uma campanha MVP tem um criativo.
CREATE TABLE ad_creative (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_id      UUID         NOT NULL REFERENCES ad_campaign(id) ON DELETE CASCADE,
    primary_text     TEXT,                             -- corpo do anuncio (message)
    headline         VARCHAR(200),
    cta              VARCHAR(40),                      -- call-to-action (ex.: LEARN_MORE)
    product_id       UUID,                             -- produto do catalogo p/ a foto; sem FK (decoupled)
    image_hash       VARCHAR(120),                     -- hash da imagem no /adimages da Meta (nullable)
    approval_status  VARCHAR(40),                      -- status de aprovacao do criativo na Meta (nullable)
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_ad_creative_campaign ON ad_creative (campaign_id);

COMMENT ON TABLE  ad_creative IS 'Criativo (texto+imagem) de uma campanha de anuncio. product_id aponta a foto do catalogo; sem FK para manter o modulo desacoplado.';
COMMENT ON COLUMN ad_creative.image_hash IS 'Hash da imagem apos upload em /act_{id}/adimages. Nullable (criativo pode ser link-only).';
