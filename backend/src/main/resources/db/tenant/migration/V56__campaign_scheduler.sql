-- V56: agendamento automatico de campanhas (CampaignSchedulerJob) — fix Fase 3.4.
-- Bug: campanhas criadas com scheduled_at ficavam DRAFT para sempre (nenhum job
-- varria a fila) e nunca disparavam. Agora a campanha com scheduled_at nasce
-- SCHEDULED e o job promove SCHEDULED -> RUNNING de forma atomica
-- (UPDATE ... WHERE status = 'SCHEDULED'), disparando UMA unica vez.
--
-- Nao ha CHECK constraint em campaigns.status (VARCHAR livre — ver V25), entao o
-- valor novo 'SCHEDULED' nao exige ALTER; esta migration cria o indice da varredura
-- do job e faz o backfill dos agendamentos ainda FUTUROS.

-- Indice da varredura do scheduler (status + scheduled_at), tambem usado pelo
-- guard atomico da transicao.
CREATE INDEX IF NOT EXISTS idx_campaigns_status_scheduled_at
  ON campaigns (status, scheduled_at);

-- Backfill: DRAFT com agendamento no FUTURO vira SCHEDULED (passa a disparar).
-- DRAFT com scheduled_at ja vencido fica como esta: disparar agora, dias depois do
-- combinado e sem acao do dono, seria pior que nao disparar (anti-ban + promo
-- velha). O dono pode inicia-la manualmente (POST /campaigns/{id}/start).
UPDATE campaigns
   SET status = 'SCHEDULED'
 WHERE status = 'DRAFT'
   AND scheduled_at IS NOT NULL
   AND scheduled_at > NOW();
