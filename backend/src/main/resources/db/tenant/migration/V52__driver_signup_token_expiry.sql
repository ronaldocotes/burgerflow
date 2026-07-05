-- V52 (Fase 6.2 / auditoria M2): validade do token de auto-cadastro do motoboy.
-- O link enviado por WhatsApp funcionava como "senha" ETERNA: sequestro do link
-- permitia concluir o cadastro e apontar a chave PIX de repasse para o atacante.
-- Aditiva: coluna nova + backfill de 72h para os tokens pendentes ja emitidos
-- (o codigo trata NULL como expirado — fail-closed).
ALTER TABLE delivery_drivers
    ADD COLUMN IF NOT EXISTS signup_token_expires_at TIMESTAMPTZ;

UPDATE delivery_drivers
   SET signup_token_expires_at = now() + interval '72 hours'
 WHERE signup_token IS NOT NULL
   AND registration_completed_at IS NULL
   AND signup_token_expires_at IS NULL;
