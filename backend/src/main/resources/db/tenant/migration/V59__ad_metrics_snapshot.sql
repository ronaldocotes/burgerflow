-- V59: snapshot diario de metricas de anuncio (Fase 8.1 — Dashboard de Insights).
-- Um job horario chama GET /act_{id}/insights (level=account, time_increment=1) e grava
-- UMA linha por (conta, dia). Como a Fase 8.2 (campanha propria) ainda nao existe, as
-- metricas sao a nivel de CONTA: agregam TODAS as campanhas que o restaurante ja roda
-- direto no Meta Ads Manager (justamente o valor: mostrar os numeros que ele ja tem).
--
-- Vive no banco do TENANT (db-per-tenant): sem coluna de escopo, o banco ja isola por
-- restaurante. Idempotente: a UNIQUE (ad_account_id, snapshot_date) permite ao job
-- re-rodar o dia (upsert) sem duplicar — o dia corrente (is_partial=true) e reescrito a
-- cada tick ate consolidar.
--
-- Dinheiro SEMPRE em centavos (BIGINT) na moeda DA CONTA (ad_account.currency); nunca
-- float. ctr_milli = CTR% * 1000 (ex.: 1.5% -> 1500) para nao guardar float tambem.
CREATE TABLE ad_metrics_snapshot (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    ad_account_id  UUID        NOT NULL REFERENCES ad_account(id) ON DELETE CASCADE,
    snapshot_date  DATE        NOT NULL,
    spend_cents    BIGINT      NOT NULL DEFAULT 0,   -- gasto do dia, centavos na moeda da conta
    impressions    BIGINT      NOT NULL DEFAULT 0,
    reach          BIGINT      NOT NULL DEFAULT 0,
    clicks         BIGINT      NOT NULL DEFAULT 0,
    ctr_milli      INT         NOT NULL DEFAULT 0,   -- CTR% * 1000 (evita float)
    cpc_cents      BIGINT      NOT NULL DEFAULT 0,   -- custo por clique, centavos na moeda da conta
    is_partial     BOOLEAN     NOT NULL DEFAULT false, -- true = dia corrente (ainda consolidando)
    fetched_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_ad_metrics_account_date UNIQUE (ad_account_id, snapshot_date)
);

-- Leitura do grafico: "ultimos N dias de UMA conta, em ordem de data". O indice da
-- UNIQUE (ad_account_id, snapshot_date) ja cobre esse filtro+ordenacao.

COMMENT ON TABLE  ad_metrics_snapshot IS 'Metricas diarias (nivel conta) da Meta por conta de anuncio. Job horario faz upsert idempotente por (conta, dia).';
COMMENT ON COLUMN ad_metrics_snapshot.spend_cents IS 'Gasto do dia em centavos na moeda da conta (BIGINT, nunca float).';
COMMENT ON COLUMN ad_metrics_snapshot.ctr_milli IS 'CTR percentual * 1000 (ex.: 1.5% -> 1500).';
COMMENT ON COLUMN ad_metrics_snapshot.cpc_cents IS 'Custo por clique em centavos na moeda da conta.';
COMMENT ON COLUMN ad_metrics_snapshot.is_partial IS 'true = linha do dia corrente (numeros ainda consolidando na Meta).';
