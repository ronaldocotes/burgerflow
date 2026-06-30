-- Fase 5.0 — Walking Skeleton Multicanal
-- Carimba a ORIGEM do pedido (canal externo) em cada linha de orders.
-- Diferente de orders.sales_channel (recorte interno COUNTER/DINE_IN/DELIVERY/ONLINE
-- usado no DRE): external_origin é a PLATAFORMA de onde o pedido entrou (OWN/IFOOD/RAPPI).
ALTER TABLE orders
  ADD COLUMN IF NOT EXISTS external_origin     VARCHAR(20)  NOT NULL DEFAULT 'OWN',
  ADD COLUMN IF NOT EXISTS external_order_id   VARCHAR(100),
  ADD COLUMN IF NOT EXISTS external_display_id VARCHAR(50);

-- Filtro por canal de origem (relatórios/KDS por plataforma).
CREATE INDEX IF NOT EXISTS idx_orders_external_origin
  ON orders(external_origin);

-- Lookup do pedido pelo id externo (idempotência futura na ingestão iFood/Rappi);
-- parcial porque a grande maioria dos pedidos é OWN e tem id externo NULL.
CREATE INDEX IF NOT EXISTS idx_orders_external_order_id
  ON orders(external_order_id) WHERE external_order_id IS NOT NULL;
