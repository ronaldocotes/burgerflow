-- Fase 1 do mecanismo de release/download do APK do app do motoboy (self-hospedado,
-- FORA da Play Store). Molde: SISATER V56__app_release.sql, adaptado ao MenuFlow.
--
-- Nivel PLATAFORMA (banco de CONTROLE, SEM escopo de tenant): o APK e o mesmo para
-- todos os restaurantes. Diferente do SISATER, o binario fica GUARDADO NO BANCO
-- (coluna apk_bytes BYTEA), nao no MinIO — o MenuFlow nao tem object storage e o
-- APK do app do motoboy e pequeno o bastante para caber no Postgres do controle.
--
-- O app consulta a ultima versao (maior version_code) via GET /public/app/latest e,
-- se for maior que a instalada, baixa via GET /public/app/download/{versionCode}.
-- Publicacao (POST /admin/app/releases) e restrita a SUPER_ADMIN.
--
-- Hibernate VALIDA (nunca altera) o schema do banco de controle no boot; a entidade
-- com.menuflow.model.control.AppRelease espelha exatamente esta tabela.
CREATE TABLE app_release (
    id            BIGSERIAL    PRIMARY KEY,
    plataforma    VARCHAR(16)  NOT NULL DEFAULT 'android',  -- 'android' (unico alvo hoje)
    version_code  INTEGER      NOT NULL,                    -- inteiro crescente; e o que o app compara
    version_name  VARCHAR(40)  NOT NULL,                    -- rotulo legivel (ex.: 1.1.0)
    notas         TEXT,                                     -- "novidades desta versao" (mostradas ao usuario)
    obrigatoria   BOOLEAN      NOT NULL DEFAULT FALSE,      -- true = trava o uso ate atualizar (fase 2)
    apk_bytes     BYTEA        NOT NULL,                    -- o APK inteiro (nivel plataforma)
    tamanho_bytes BIGINT       NOT NULL,                    -- tamanho em bytes (== length(apk_bytes))
    sha256        VARCHAR(64),                              -- integridade do download (hex, 64 chars)
    criado_em     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- No maximo UMA linha por (plataforma, version_code): impede sobrescrever um
    -- release ja publicado (a publicacao rejeita com 409 antes; este e o cinto de
    -- seguranca no banco).
    CONSTRAINT uq_app_release_plataforma_version UNIQUE (plataforma, version_code)
);

-- Acelera o GET /public/app/latest (maior version_code por plataforma).
CREATE INDEX idx_app_release_latest ON app_release (plataforma, version_code DESC);
