-- MenuFlow CONTROL database baseline — Flyway V1.
-- The control DB is the GLOBAL registry: tenants, users (auth) and the
-- per-tenant migration ledger. It NEVER holds business data (that lives in
-- each tenant_<slug> database). Money/business tables belong to the tenant PU.
--
-- Column names follow Spring's default physical naming (camelCase -> snake_case)
-- so they match the JPA @Entity mappings in com.menuflow.model.control.
-- The control EMF runs with hibernate.ddl-auto = validate, so this DDL is the
-- single source of truth — keep it in sync with the entities, rolling forward.
--
-- NEVER edit this file once applied: Flyway tracks it by checksum. New changes
-- go in V2, V3, ... (one logical change per migration).

-- ── tenants ──────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS tenants (
    id                uuid PRIMARY KEY,
    slug              varchar(255) NOT NULL UNIQUE,
    display_name      varchar(255) NOT NULL,
    subscription_plan varchar(32)  NOT NULL DEFAULT 'BASIC',
    is_active         boolean      NOT NULL DEFAULT true,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    expires_at        timestamptz
);

-- ── users (auth principal) ───────────────────────────────────────────────────
-- Login is scoped by (tenant_id, email): the same email may exist across
-- different hamburguerias, so email is UNIQUE only WITHIN a tenant.
CREATE TABLE IF NOT EXISTS users (
    id            uuid PRIMARY KEY,
    tenant_id     uuid         NOT NULL,
    email         varchar(255) NOT NULL,
    password_hash varchar(255) NOT NULL,
    first_name    varchar(255) NOT NULL,
    last_name     varchar(255) NOT NULL DEFAULT '',
    role          varchar(32)  NOT NULL DEFAULT 'STAFF',
    is_active     boolean      NOT NULL DEFAULT true,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    last_login_at timestamptz,
    CONSTRAINT uq_users_tenant_email UNIQUE (tenant_id, email)
);
-- Auth lookups and admin listings filter by tenant; index the FK-ish column.
CREATE INDEX IF NOT EXISTS idx_users_tenant ON users (tenant_id);

-- ── tenant_migration_log (per-tenant Flyway ledger) ──────────────────────────
-- Append-only audit: every TenantFlywayMigrator.migrate() writes one row with
-- the version it left the tenant at, whether it succeeded, and the error if not.
-- Read by GET /api/admin/tenants/migration-status to detect drift.
CREATE TABLE IF NOT EXISTS tenant_migration_log (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_slug      varchar(255) NOT NULL,
    version_applied  varchar(50)  NOT NULL,
    applied_at       timestamptz  NOT NULL DEFAULT now(),
    success          boolean      NOT NULL,
    error_msg        text
);
CREATE INDEX IF NOT EXISTS idx_tenant_migration_log_slug
    ON tenant_migration_log (tenant_slug, applied_at DESC);

-- ── refresh_tokens (Sprint 2 — Craudio) ──────────────────────────────────────
-- Placeholder: the refresh_tokens table is owned by the auth work in V2.
-- See V2__refresh_tokens.sql (left for Craudio to fill in). Do NOT add the
-- columns here — rolling it as its own migration keeps history clean and lets
-- the auth change land independently of this baseline.
