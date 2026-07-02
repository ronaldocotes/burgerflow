-- Fase B2: motoboy PROVISIONAL (freelancer que entrou pelo grupo) + funil de recrutamento.
-- Um entregador criado pelo aceite no grupo nasce provisional=true, driver_type='FREELANCER';
-- apos a 1a entrega concluida (first_delivery_at) recebe o convite de cadastro (signup_token).
ALTER TABLE delivery_drivers
  ADD COLUMN IF NOT EXISTS driver_type          VARCHAR(12) NOT NULL DEFAULT 'FROTA'
                                                CHECK (driver_type IN ('FROTA', 'FREELANCER')),
  ADD COLUMN IF NOT EXISTS provisional          BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS first_delivery_at    TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS signup_token         UUID,
  ADD COLUMN IF NOT EXISTS recruitment_sent_at  TIMESTAMPTZ;

-- Telefone unico (quando informado): o find-or-create do despacho casa o motoboy pelo telefone.
CREATE UNIQUE INDEX IF NOT EXISTS idx_delivery_driver_phone_unique
  ON delivery_drivers (phone)
  WHERE phone IS NOT NULL;

-- Token de cadastro unico (quando presente): usado no link publico de auto-cadastro do freelancer.
CREATE UNIQUE INDEX IF NOT EXISTS idx_delivery_driver_signup_token
  ON delivery_drivers (signup_token)
  WHERE signup_token IS NOT NULL;
