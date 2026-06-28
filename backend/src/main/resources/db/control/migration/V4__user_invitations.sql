-- MenuFlow CONTROL — convites de usuário (user_invitations) — Flyway V4.
-- O módulo de usuários vive no banco de CONTROLE (junto de users/tenants). Um convite
-- guarda só o HASH do token (nunca o token cru) e expira em 72h. O controle roda com
-- hibernate.ddl-auto=validate, então este DDL é a fonte de verdade — manter em sincronia
-- com a entidade UserInvitation. NUNCA editar após aplicada (Flyway rastreia por checksum).

CREATE TABLE user_invitations (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID         NOT NULL,
    email              VARCHAR(255) NOT NULL,
    role               VARCHAR(32)  NOT NULL DEFAULT 'STAFF',
    token_hash         VARCHAR(128) NOT NULL,
    status             VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    invited_by_user_id UUID         NOT NULL,
    expires_at         TIMESTAMPTZ  NOT NULL,
    accepted_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_invitation_status CHECK (status IN ('PENDING','ACCEPTED','REVOKED','EXPIRED'))
);

-- No máximo UM convite PENDENTE por (tenant, e-mail) — case-insensitive. Convites
-- aceitos/revogados/expirados não bloqueiam um novo convite ao mesmo e-mail.
CREATE UNIQUE INDEX uq_invitation_pending ON user_invitations (tenant_id, lower(email)) WHERE status = 'PENDING';
CREATE UNIQUE INDEX uq_invitation_token   ON user_invitations (token_hash);
CREATE INDEX        idx_invitation_tenant ON user_invitations (tenant_id);
