-- MenuFlow TENANT — trilha de auditoria (audit_log) — Flyway V17.
-- Append-only: registra quem fez o quê em qual entidade, com snapshot antes/depois
-- em JSONB, motivo, IP e user-agent. Vive no banco do tenant (db-per-tenant), então
-- já está naturalmente escopado ao restaurante. NUNCA editar após aplicada (Flyway
-- rastreia por checksum). Linha de auditoria não é alterada nem removida.

CREATE TABLE audit_log (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id UUID         NOT NULL,
    actor_role    VARCHAR(32),
    action        VARCHAR(64)  NOT NULL,
    entity        VARCHAR(64)  NOT NULL,
    entity_id     UUID,
    before_json   JSONB,
    after_json    JSONB,
    reason        TEXT,
    ip            VARCHAR(45),
    user_agent    TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_entity  ON audit_log (entity, entity_id);
CREATE INDEX idx_audit_actor   ON audit_log (actor_user_id);
CREATE INDEX idx_audit_created ON audit_log (created_at DESC);
CREATE INDEX idx_audit_action  ON audit_log (action);
