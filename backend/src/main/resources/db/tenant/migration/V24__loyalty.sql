-- MenuFlow TENANT — Programa de Fidelidade (punch-card) — Flyway V24.
-- Cliente acumula pontos por R$ gasto; ao atingir o limite ganha uma recompensa
-- (punch). Tudo db-per-tenant (1 restaurante por banco). Pontos sao inteiros.
-- NOTE: never edit this file once applied — Flyway tracks by checksum.

-- Config do programa de fidelidade por tenant (linha unica em tenant_config).
ALTER TABLE tenant_config ADD COLUMN IF NOT EXISTS loyalty_enabled BOOLEAN NOT NULL DEFAULT FALSE;
-- Pontos creditados por R$1,00 gasto (ex.: 1 ponto a cada R$1).
ALTER TABLE tenant_config ADD COLUMN IF NOT EXISTS loyalty_points_per_real INTEGER NOT NULL DEFAULT 1;
-- Pontos necessarios para desbloquear 1 recompensa (1 punch).
ALTER TABLE tenant_config ADD COLUMN IF NOT EXISTS loyalty_reward_threshold INTEGER NOT NULL DEFAULT 100;
-- Texto exibido/enviado ao cliente quando a recompensa e desbloqueada.
ALTER TABLE tenant_config ADD COLUMN IF NOT EXISTS loyalty_reward_description VARCHAR(200) DEFAULT 'Recompensa desbloqueada!';

-- Historico de transacoes de pontos (APPEND-ONLY: nunca deletar nem atualizar).
-- points_delta positivo = ganhou; negativo = resgatou/ajuste.
CREATE TABLE loyalty_transactions (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id  UUID         NOT NULL REFERENCES customers(id),
    order_id     UUID         REFERENCES orders(id),
    points_delta INTEGER      NOT NULL,
    reason       VARCHAR(50)  NOT NULL,
    description  VARCHAR(200),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_loyalty_tx_reason
        CHECK (reason IN ('ORDER_PAID', 'REWARD_REDEEMED', 'MANUAL_ADJUST', 'EXPIRY'))
);
CREATE INDEX idx_loyalty_tx_customer ON loyalty_transactions (customer_id);
CREATE INDEX idx_loyalty_tx_order    ON loyalty_transactions (order_id);
-- Idempotencia do credito por pedido: o credito ORDER_PAID acontece no maximo uma
-- vez por pedido (o listener pode ser reentregue). Indice parcial UNIQUE garante a
-- invariante no banco, alem da checagem no servico (defesa em profundidade).
CREATE UNIQUE INDEX uq_loyalty_tx_order_paid
    ON loyalty_transactions (order_id) WHERE reason = 'ORDER_PAID';

-- Recompensas desbloqueadas (punch ganho). expires_at NULL = nao expira.
CREATE TABLE loyalty_rewards (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id       UUID         NOT NULL REFERENCES customers(id),
    earned_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    redeemed_at       TIMESTAMPTZ,
    redeemed_order_id UUID         REFERENCES orders(id),
    expires_at        TIMESTAMPTZ
);
CREATE INDEX idx_loyalty_rewards_customer ON loyalty_rewards (customer_id);
-- Consulta frequente: punches ainda nao resgatados de um cliente.
CREATE INDEX idx_loyalty_rewards_unredeemed
    ON loyalty_rewards (customer_id) WHERE redeemed_at IS NULL;
