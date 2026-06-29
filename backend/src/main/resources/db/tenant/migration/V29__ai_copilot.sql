-- MenuFlow TENANT — Copiloto do dono (Fase 4.1) — Flyway V29.
-- Adiciona a configuracao de IA ao tenant_config e cria o historico de conversas do
-- copiloto. Tudo no banco do TENANT (db-per-tenant): nenhuma coluna de escopo, o banco
-- ja isola por restaurante. Dinheiro nao se aplica aqui.

-- --- Configuracao de IA (toggles na unica linha de tenant_config) ---
ALTER TABLE tenant_config
    ADD COLUMN IF NOT EXISTS ai_enabled       BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS ai_system_prompt TEXT,                       -- personalizacao opcional do prompt de sistema
    ADD COLUMN IF NOT EXISTS ai_daily_limit   INTEGER NOT NULL DEFAULT 30; -- perguntas/dia por tenant (anti-abuso/custo)

-- --- Historico de conversas do copiloto ---
-- Append-only: cada mensagem (user/assistant/tool) e uma linha. A sessao agrupa o
-- dialogo (session_id gerado pelo frontend). Sem escopo de tenant: db-per-tenant isola.
CREATE TABLE ai_conversations (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id  VARCHAR(100) NOT NULL,
    role        VARCHAR(20)  NOT NULL,   -- 'user' | 'assistant' | 'tool'
    content     TEXT,
    tool_name   VARCHAR(100),
    tool_result TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Leitura do historico de uma sessao em ordem cronologica + contagem por sessao.
CREATE INDEX idx_ai_conv_session ON ai_conversations (session_id, created_at);
-- Rate limit diario: conta as mensagens 'user' do dia (filtra por role + created_at).
CREATE INDEX idx_ai_conv_role_created ON ai_conversations (role, created_at);
