-- Snapshot dos complementos escolhidos por item de pedido. option_id referencia o
-- catálogo, mas NÃO é FK (é snapshot — a opção pode ser removida do cardápio sem
-- afetar pedidos antigos). order_item_id é FK com CASCADE para limpar com o item.

CREATE TABLE order_item_options (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_item_id UUID NOT NULL REFERENCES order_items(id) ON DELETE CASCADE,
    option_id UUID NOT NULL,
    group_name VARCHAR(100) NOT NULL,
    option_name VARCHAR(100) NOT NULL,
    price_cents BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_order_item_options_item ON order_item_options (order_item_id);
