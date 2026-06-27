-- MenuFlow TENANT — configurações operacionais do restaurante (Flyway V13).
-- Uma linha por banco de tenant (db-per-tenant). Hoje guarda só o aceite
-- automático de pedidos; desenhada para crescer com novos toggles (colunas
-- aditivas). NOTE: never edit this file once applied — Flyway tracks by checksum.

CREATE TABLE IF NOT EXISTS tenant_config (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Pedido novo nasce em PREPARING (direto na cozinha) em vez de PENDING.
    auto_accept_orders  boolean NOT NULL DEFAULT false,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);

-- Semeia a linha única de configuração (default: aceite automático desligado).
-- Guardado por NOT EXISTS para ser idempotente caso a tabela já tenha conteúdo.
INSERT INTO tenant_config (auto_accept_orders)
SELECT false
WHERE NOT EXISTS (SELECT 1 FROM tenant_config);
