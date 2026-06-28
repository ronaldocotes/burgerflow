-- Fase 2.4: telefone do cliente no pedido, para notificacao por WhatsApp (WAHA).
-- Opt-in por pedido: quando preenchido, os marcos de status disparam um aviso.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS customer_phone VARCHAR(20);
