-- Log de auditoria de toda ação do painel super-admin.
-- Append-only: sem UPDATE/DELETE na aplicação.
-- before_json/after_json NUNCA devem conter segredos ou PII em claro — mascarar na aplicação.
CREATE TABLE platform_audit_log (
    id               BIGSERIAL PRIMARY KEY,
    actor_user_id    UUID         NOT NULL,
    actor_email      VARCHAR(255) NOT NULL,
    action           VARCHAR(60)  NOT NULL,   -- TENANT_CREATE, TENANT_ACTIVATE, TENANT_DEACTIVATE, MODULE_TOGGLE, KEY_ROTATE, MIGRATE, ...
    target_tenant_id UUID,                     -- NULL quando ação é sobre a plataforma em si
    target_entity    VARCHAR(60),
    before_json      JSONB,
    after_json       JSONB,
    source_ip        VARCHAR(45),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_platform_audit_tenant ON platform_audit_log(target_tenant_id, created_at DESC);
CREATE INDEX idx_platform_audit_actor  ON platform_audit_log(actor_user_id, created_at DESC);
CREATE INDEX idx_platform_audit_action ON platform_audit_log(action, created_at DESC);

COMMENT ON TABLE platform_audit_log IS 'Trilha de auditoria imutável de todas as ações do super-admin no /plataforma. Append-only.';
COMMENT ON COLUMN platform_audit_log.action IS 'TENANT_CREATE | TENANT_ACTIVATE | TENANT_DEACTIVATE | MODULE_TOGGLE | KEY_ROTATE | MIGRATE | PLATFORM_USER_INVITE | PLATFORM_USER_REVOKE';
COMMENT ON COLUMN platform_audit_log.before_json IS 'Estado anterior (mascarado — sem segredos/PII em claro)';
COMMENT ON COLUMN platform_audit_log.after_json  IS 'Estado posterior (mascarado — sem segredos/PII em claro)';
