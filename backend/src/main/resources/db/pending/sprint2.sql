-- BurgerFlow Sprint 2 — additive TENANT schema (database-per-tenant).
-- Money is in CENTAVOS (bigint). Idempotent (IF NOT EXISTS / ADD COLUMN IF NOT
-- EXISTS) so it is safe to re-run on every tenant on first access.
--
-- NOTE FOR THE CURADOR: this lives in db/pending/ on purpose. Fold it into the
-- Flyway baseline / a new versioned migration (e.g. V2__sprint2.sql) when you
-- incorporate Sprint 2. The runtime TenantSchemaInitializer applies db/pending/*
-- after the baseline so the tables exist while Flyway is being wired up.

-- 1) Delivery dispatch columns on orders -------------------------------------
ALTER TABLE orders ADD COLUMN IF NOT EXISTS driver_id       uuid;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_status varchar(32);
CREATE INDEX IF NOT EXISTS idx_orders_driver ON orders (driver_id);
CREATE INDEX IF NOT EXISTS idx_orders_delivery_status ON orders (delivery_status);

-- 2) Payments (PDV) -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS payments (
    id                uuid PRIMARY KEY,
    order_id          uuid NOT NULL,
    method            varchar(16) NOT NULL,
    amount_paid_cents bigint NOT NULL,
    change_cents      bigint NOT NULL DEFAULT 0,
    paid_at           timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_payments_order ON payments (order_id);

-- 3) Delivery drivers ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS delivery_drivers (
    id            uuid PRIMARY KEY,
    name          varchar(255) NOT NULL,
    phone         varchar(64)  NOT NULL,
    license_plate varchar(32),
    active        boolean NOT NULL DEFAULT true,
    tenant_id     uuid NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_drivers_active ON delivery_drivers (active);

-- 4) Refresh tokens (revocable sessions) --------------------------------------
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id         uuid PRIMARY KEY,
    user_id    uuid NOT NULL,
    token_hash varchar(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    revoked    boolean NOT NULL DEFAULT false,
    tenant_id  uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz
);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user ON refresh_tokens (user_id);
