-- Fase 5.5a — log de eventos Open Delivery (99Food / Rappi) no banco do TENANT.
-- Mesmo modelo do V33 (ifood_event_log): registra cada evento recebido no polling
-- para idempotencia (UNIQUE event_id+platform), auditoria e reprocessamento.
-- Nesta fase (5.5a) so a tabela existe; o poller real (persistir/ACK/materializar
-- pedido) entra na 5.5b.
CREATE TABLE open_delivery_event_log (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  event_id       VARCHAR(80)  NOT NULL,
  platform       VARCHAR(15)  NOT NULL CHECK (platform IN ('NINETY_NINE','RAPPI')),
  event_code     VARCHAR(40)  NOT NULL,
  od_order_id    VARCHAR(64),
  payload        JSONB        NOT NULL,
  status         VARCHAR(10)  NOT NULL DEFAULT 'PENDING'
                 CHECK (status IN ('PENDING','PROCESSED','FAILED')),
  retry_count    INT          NOT NULL DEFAULT 0,
  received_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  acked_at       TIMESTAMPTZ,
  processed_at   TIMESTAMPTZ,
  CONSTRAINT uq_od_event UNIQUE (event_id, platform)
);
-- Indice parcial: o poller busca so o backlog nao-processado (PENDING/FAILED).
CREATE INDEX idx_od_event_pending ON open_delivery_event_log(status)
  WHERE status <> 'PROCESSED';
