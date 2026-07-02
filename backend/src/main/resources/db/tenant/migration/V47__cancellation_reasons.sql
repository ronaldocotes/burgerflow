-- Fase CONFIG-A / issue #10: motivos de cancelamento pre-cadastrados.
-- Lista editavel (em vez de texto livre) usada no fluxo de cancelar pedido do
-- KDS/PDV. Facilita relatorio de motivo no DRE/growth. NOTE: never edit once applied.
CREATE TABLE IF NOT EXISTS cancellation_reasons (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    description varchar(140) NOT NULL,
    active      boolean NOT NULL DEFAULT true,
    sort_order  int NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

-- Descricao unica somente entre motivos ativos (um motivo desativado libera o
-- texto para reuso). Indice parcial, mesmo padrao das mesas (V11).
CREATE UNIQUE INDEX IF NOT EXISTS uq_cancellation_reason_desc_active
    ON cancellation_reasons (description) WHERE active = true;

-- Semeia motivos comuns (idempotente): so semeia se a tabela estiver vazia.
INSERT INTO cancellation_reasons (description, sort_order)
SELECT v.description, v.sort_order
FROM (VALUES
    ('Cliente desistiu', 10),
    ('Endereco fora da area de entrega', 20),
    ('Item em falta no estoque', 30),
    ('Pedido em duplicidade', 40),
    ('Problema no pagamento', 50)
) AS v(description, sort_order)
WHERE NOT EXISTS (SELECT 1 FROM cancellation_reasons);

-- Vincula (opcional) o motivo escolhido ao pedido cancelado. Mantemos tambem o
-- texto denormalizado em orders.cancelled_reason (snapshot), para o motivo
-- sobreviver a edicao/exclusao do catalogo. Sem FK rigida: o catalogo pode mudar.
ALTER TABLE orders
  ADD COLUMN IF NOT EXISTS cancelled_reason_id uuid;
