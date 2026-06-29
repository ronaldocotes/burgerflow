-- Fase 4.3 — Bot WhatsApp inbound.
-- Configuracoes do bot (atendente virtual) no tenant_config + tabela de handoffs
-- (transferencia para atendente humano). Tudo no banco do TENANT (db-per-tenant).

-- Toggles e textos do bot. Colunas aditivas na linha unica de tenant_config.
ALTER TABLE tenant_config
  ADD COLUMN IF NOT EXISTS bot_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS bot_system_prompt TEXT,
  ADD COLUMN IF NOT EXISTS bot_handoff_keyword VARCHAR(50) DEFAULT 'atendente',
  ADD COLUMN IF NOT EXISTS bot_welcome_message TEXT DEFAULT 'Olá! Sou o assistente virtual. Como posso ajudar?',
  ADD COLUMN IF NOT EXISTS bot_handoff_message TEXT DEFAULT 'Transferindo para um atendente humano. Aguarde!',
  -- Horarios de funcionamento por dia da semana. Formato "HH:mm-HH:mm" (ex.: "11:00-23:00").
  -- NULL = fechado naquele dia.
  ADD COLUMN IF NOT EXISTS opening_hours_monday VARCHAR(20),
  ADD COLUMN IF NOT EXISTS opening_hours_tuesday VARCHAR(20),
  ADD COLUMN IF NOT EXISTS opening_hours_wednesday VARCHAR(20),
  ADD COLUMN IF NOT EXISTS opening_hours_thursday VARCHAR(20),
  ADD COLUMN IF NOT EXISTS opening_hours_friday VARCHAR(20),
  ADD COLUMN IF NOT EXISTS opening_hours_saturday VARCHAR(20),
  ADD COLUMN IF NOT EXISTS opening_hours_sunday VARCHAR(20);

-- Handoffs: quando o cliente pede atendente humano (ou o bot transfere), abrimos um
-- handoff. Enquanto NAO resolvido, o bot silencia para aquele telefone (o humano assumiu).
CREATE TABLE IF NOT EXISTS bot_handoffs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_phone VARCHAR(30) NOT NULL,
  last_bot_message TEXT,
  resolved BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  resolved_at TIMESTAMPTZ
);

-- Consulta quente: "existe handoff ativo para este telefone?" (por telefone + resolved).
CREATE INDEX IF NOT EXISTS idx_bot_handoffs_phone ON bot_handoffs(customer_phone, resolved);
