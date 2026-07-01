-- Fase 5.1a — log de eventos recebidos do iFood (polling/webhook), no banco do
-- TENANT. Idempotencia por UNIQUE(event_id): reentrega do mesmo evento NAO
-- reprocessa. status controla o consumo (PENDING -> PROCESSED/FAILED); indice
-- parcial cobre so os nao-processados (fila de trabalho enxuta).
CREATE TABLE ifood_event_log (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  event_id       VARCHAR(80)  NOT NULL,
  merchant_id    VARCHAR(64)  NOT NULL,
  event_code     VARCHAR(40)  NOT NULL,
  ifood_order_id VARCHAR(64),
  payload        JSONB        NOT NULL,
  status         VARCHAR(10)  NOT NULL DEFAULT 'PENDING'
                 CHECK (status IN ('PENDING','PROCESSED','FAILED')),
  retry_count    INT          NOT NULL DEFAULT 0,
  received_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  acked_at       TIMESTAMPTZ,
  processed_at   TIMESTAMPTZ,
  CONSTRAINT uq_ifood_event UNIQUE (event_id)
);
CREATE INDEX idx_ifood_event_pending ON ifood_event_log(status)
  WHERE status <> 'PROCESSED';
