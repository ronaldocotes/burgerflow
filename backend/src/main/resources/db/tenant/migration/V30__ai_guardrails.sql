-- MenuFlow TENANT — Hardening do Copiloto (Fase 4.2) — Flyway V30.
-- Adiciona guardrails de prompt injection ao tenant_config e instrumentacao de
-- latencia/tokens ao historico de conversas. Tudo no banco do TENANT (db-per-tenant):
-- nenhuma coluna de escopo, o banco ja isola por restaurante.

-- --- Guardrails de prompt injection (toggles na unica linha de tenant_config) ---
ALTER TABLE tenant_config
    -- Teto de caracteres da mensagem do usuario; acima disso truncamos silenciosamente.
    ADD COLUMN IF NOT EXISTS ai_max_message_length INTEGER NOT NULL DEFAULT 2000,
    -- JSON array de regexes EXTRAS bloqueados pelo restaurante (os padroes default sao
    -- hardcoded na aplicacao; esta coluna apenas acrescenta). Null/vazio = so os default.
    ADD COLUMN IF NOT EXISTS ai_blocked_patterns TEXT;

-- --- Observabilidade do historico de conversas ---
-- latency_ms: tempo de execucao da ferramenta (preenchido nas linhas role='tool').
ALTER TABLE ai_conversations ADD COLUMN IF NOT EXISTS latency_ms INTEGER;
-- total_tokens: prompt+completion da rodada (preenchido na linha final role='assistant'),
-- base das metricas diarias de consumo (GET /ai/metrics). O ledger de billing mensal
-- continua em control.ai_usage; aqui guardamos a granularidade por mensagem.
ALTER TABLE ai_conversations ADD COLUMN IF NOT EXISTS total_tokens INTEGER;
