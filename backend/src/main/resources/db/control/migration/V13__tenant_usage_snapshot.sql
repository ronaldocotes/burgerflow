-- Snapshot diário de métricas por tenant, preenchido por job noturno.
-- Evita consulta N-bancos síncrona na listagem do painel super-admin.
-- Append-only na prática: o job insere 1 linha/dia por tenant (UPSERT via ON CONFLICT).
-- Campos:
--   orders_month  → pedidos no mês corrente (calculado pelo job)
--   db_size_mb    → tamanho do banco tenant em MB (pg_database_size / 1024^2)
--   last_login_at → última vez que qualquer usuário daquele tenant gerou um JWT
CREATE TABLE tenant_usage_snapshot (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    snapshot_date    DATE        NOT NULL,
    orders_month     BIGINT      NOT NULL DEFAULT 0,
    db_size_mb       BIGINT      NOT NULL DEFAULT 0,
    last_login_at    TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, snapshot_date)
);

-- Índice composto para busca do snapshot mais recente por tenant (ORDER BY snapshot_date DESC).
CREATE INDEX idx_usage_snapshot_tenant_date ON tenant_usage_snapshot(tenant_id, snapshot_date DESC);
