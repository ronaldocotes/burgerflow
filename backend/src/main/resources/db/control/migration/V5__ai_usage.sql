-- MenuFlow CONTROL — uso de IA por tenant/mes (ai_usage) — Flyway V5.
-- Telemetria de consumo do Copiloto (Fase 4.1) para billing: tokens e numero de
-- requisicoes agregados por tenant e mes. Vive no banco de CONTROLE (global), nao
-- no banco do tenant, justamente para o faturamento ser consolidavel por empresa.
--
-- O controle roda hibernate.ddl-auto=validate, entao este DDL e a fonte de verdade —
-- manter em sincronia com a entidade com.menuflow.model.control.AiUsage. NUNCA editar
-- apos aplicada (Flyway rastreia por checksum); mudancas seguem em V6, V7, ...
--
-- DECISAO GROUNDED (desvio do spec original): o spec pedia
-- `company_id UUID REFERENCES companies(id)`, mas no MenuFlow NAO existe tabela
-- `companies` — o registro global de tenant e a tabela `tenants` (id). Alem disso,
-- registro de consumo/faturamento NAO deve sumir se o tenant for removido (historico
-- financeiro). Por isso tenant_id e um UUID indexado SEM foreign key/cascade — o
-- ledger de uso sobrevive ao ciclo de vida do tenant. tenant_slug acompanha para
-- leitura humana/auditoria.
CREATE TABLE ai_usage (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID         NOT NULL,
    tenant_slug       VARCHAR(255) NOT NULL,
    month_year        VARCHAR(7)   NOT NULL,  -- "2026-06" (America/Sao_Paulo)
    prompt_tokens     BIGINT       NOT NULL DEFAULT 0,
    completion_tokens BIGINT       NOT NULL DEFAULT 0,
    total_requests    BIGINT       NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Uma linha por (tenant, mes): o acumulado do mes faz upsert sobre esta chave.
CREATE UNIQUE INDEX uq_ai_usage_tenant_month ON ai_usage (tenant_id, month_year);
