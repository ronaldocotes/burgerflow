-- Disponibilidade do produto por CANAL de venda e por JANELA de horário.
-- Sem registros de canal  = disponível em TODOS os canais (default permissivo).
-- Sem janelas de horário  = disponível em QUALQUER horário (default permissivo).

CREATE TABLE product_channels (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    channel VARCHAR(20) NOT NULL,
    UNIQUE (product_id, channel)
);
CREATE INDEX idx_product_channels_product ON product_channels (product_id);

CREATE TABLE product_availability_windows (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    day_of_week SMALLINT NOT NULL,  -- 1=segunda .. 7=domingo (ISO-8601)
    start_minute INT NOT NULL,      -- minutos desde 00:00 (0..1439)
    end_minute INT NOT NULL
);
CREATE INDEX idx_avail_windows_product ON product_availability_windows (product_id);
