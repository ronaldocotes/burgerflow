-- MenuFlow TENANT — Carrinho Abandonado (recuperacao) — Flyway V26.
-- Cada pedido que nasce PENDENTE de pagamento com telefone do cliente vira uma
-- "cart_session" ACTIVE; um job periodico, depois de um atraso configuravel, envia
-- uma mensagem de recuperacao por WhatsApp. Pagamento -> RECOVERED; sem pagar dentro
-- do prazo -> EXPIRED. Tudo db-per-tenant (1 restaurante por banco).
-- NOTE: never edit this file once applied — Flyway tracks by checksum.

CREATE TABLE cart_sessions (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id                 UUID        NOT NULL REFERENCES orders(id),
    customer_phone           VARCHAR(20),
    total_cents              BIGINT      NOT NULL,
    -- ACTIVE: aguardando; RECOVERED: pedido foi pago; SENT: mensagem enviada;
    -- EXPIRED: passou o prazo sem pagar (nao tenta mais).
    status                   VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    recovery_message_sent_at TIMESTAMPTZ,
    recovered_at             TIMESTAMPTZ,
    expired_at               TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_cart_sessions_order ON cart_sessions(order_id);
CREATE INDEX idx_cart_sessions_status ON cart_sessions(status);
-- Uma comanda de recuperacao por pedido (idempotencia da criacao).
CREATE UNIQUE INDEX uq_cart_session_order ON cart_sessions(order_id);

-- Config de recuperacao por tenant (linha unica em tenant_config).
-- Liga/desliga a recuperacao de carrinho abandonado.
ALTER TABLE tenant_config ADD COLUMN IF NOT EXISTS cart_recovery_enabled BOOLEAN NOT NULL DEFAULT FALSE;
-- Atraso (minutos) apos a criacao do pedido antes de enviar a mensagem.
ALTER TABLE tenant_config ADD COLUMN IF NOT EXISTS cart_recovery_delay_minutes INTEGER NOT NULL DEFAULT 30;
-- Mensagem com placeholders {nome}, {total} e {link}.
ALTER TABLE tenant_config ADD COLUMN IF NOT EXISTS cart_recovery_message TEXT DEFAULT '🛒 Olá {nome}! Você deixou itens no carrinho. Que tal finalizar seu pedido de {total}? Acesse: {link}';
-- Prazo (horas) para o carrinho expirar sem pagamento; depois disso, nao envia mais.
ALTER TABLE tenant_config ADD COLUMN IF NOT EXISTS cart_recovery_expiry_hours INTEGER NOT NULL DEFAULT 2;
