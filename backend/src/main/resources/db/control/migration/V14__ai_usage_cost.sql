-- MenuFlow CONTROL — adiciona custo estimado ao ledger de uso de IA (ai_usage) — Flyway V14.
--
-- V11 era um placeholder reservado para este DDL; a implementacao real fica aqui em V14
-- porque V12/V13 foram aplicadas antes desta fase ser prioritizada.
--
-- Unidade: micros de USD (1 USD = 1_000_000 micros). Armazenar como BIGINT evita
-- ponto flutuante no banco — padrao adotado em toda a camada financeira do MenuFlow.
-- O valor e um snapshot calculado no momento da gravacao com base na tabela de precos
-- vigente (AiPricingTable.kt). NAO recalcular retroativamente: linhas existentes ficam
-- com 0 (default), o que e semanticamente correto (custo desconhecido, nao zero real).
ALTER TABLE ai_usage
    ADD COLUMN IF NOT EXISTS estimated_cost_usd_micros BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN ai_usage.estimated_cost_usd_micros IS
    'Custo estimado em micros de USD (1 USD = 1_000_000 micros). Snapshot na gravacao — nao recalcular retroativamente.';
