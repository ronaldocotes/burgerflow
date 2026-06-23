-- BurgerFlow TENANT baseline schema (database-per-tenant) — Flyway V1.
-- Applied once per tenant database via TenantFlywayMigrator on first access.
-- Money is in CENTAVOS (bigint). IF NOT EXISTS keeps it safe even if a tenant
-- DB was pre-seeded by the old ScriptUtils path before this migration existed.
-- NOTE: never edit this file once applied — Flyway tracks it by checksum.
--       Schema changes roll FORWARD as V2, V3, ... (one logical change each).

CREATE TABLE IF NOT EXISTS categories (
    id            uuid PRIMARY KEY,
    name          varchar(255) NOT NULL UNIQUE,
    description   varchar(255) NOT NULL DEFAULT '',
    display_order int NOT NULL DEFAULT 0,
    active        boolean NOT NULL DEFAULT true,
    color_code    varchar(255),
    icon_url      varchar(255),
    version       bigint NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS products (
    id                        uuid PRIMARY KEY,
    category_id               uuid NOT NULL,
    sku                       varchar(255) NOT NULL UNIQUE,
    name                      varchar(255) NOT NULL,
    description               varchar(255) NOT NULL DEFAULT '',
    price_cents               bigint NOT NULL,
    cost_price_cents          bigint,
    image_url                 varchar(255),
    active                    boolean NOT NULL DEFAULT true,
    is_available              boolean NOT NULL DEFAULT true,
    display_order             int NOT NULL DEFAULT 0,
    preparation_time_minutes  int NOT NULL DEFAULT 10,
    is_featured               boolean NOT NULL DEFAULT false,
    version                   bigint NOT NULL DEFAULT 0,
    created_at                timestamptz NOT NULL DEFAULT now(),
    updated_at                timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_products_category ON products (category_id);
CREATE INDEX IF NOT EXISTS idx_products_active ON products (active);

CREATE TABLE IF NOT EXISTS ingredients (
    id              uuid PRIMARY KEY,
    name            varchar(255) NOT NULL UNIQUE,
    description     varchar(255) NOT NULL DEFAULT '',
    unit            varchar(32) NOT NULL DEFAULT 'UNIT',
    unit_cost_cents bigint NOT NULL DEFAULT 0,
    stock_quantity  double precision NOT NULL DEFAULT 0,
    min_stock       double precision NOT NULL DEFAULT 0,
    active          boolean NOT NULL DEFAULT true,
    is_allergen     boolean NOT NULL DEFAULT false,
    version         bigint NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS product_ingredients (
    product_id    uuid NOT NULL,
    ingredient_id uuid NOT NULL,
    quantity      double precision NOT NULL,
    unit          varchar(32) NOT NULL DEFAULT 'UNIT',
    is_optional   boolean NOT NULL DEFAULT false,
    display_order int NOT NULL DEFAULT 0,
    PRIMARY KEY (product_id, ingredient_id)
);
CREATE INDEX IF NOT EXISTS idx_pi_ingredient ON product_ingredients (ingredient_id);

CREATE TABLE IF NOT EXISTS customers (
    id                 uuid PRIMARY KEY,
    name               varchar(255) NOT NULL,
    phone_number       varchar(255) NOT NULL UNIQUE,
    email              varchar(255) UNIQUE,
    address_line_1     varchar(255),
    neighborhood       varchar(255),
    city               varchar(255),
    zip_code           varchar(255),
    delivery_fee_cents bigint,
    loyalty_points     int NOT NULL DEFAULT 0,
    active             boolean NOT NULL DEFAULT true,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS orders (
    id                          uuid PRIMARY KEY,
    order_number                varchar(255) NOT NULL UNIQUE,
    customer_id                 uuid,
    user_id                     uuid,
    order_type                  varchar(32) NOT NULL DEFAULT 'DINE_IN',
    status                      varchar(32) NOT NULL DEFAULT 'PENDING',
    table_number                varchar(255),
    notes                       varchar(255),
    subtotal_cents              bigint NOT NULL DEFAULT 0,
    discount_cents              bigint NOT NULL DEFAULT 0,
    delivery_fee_cents          bigint NOT NULL DEFAULT 0,
    total_cents                 bigint NOT NULL DEFAULT 0,
    payment_method              varchar(32),
    payment_status              varchar(32) NOT NULL DEFAULT 'PENDING',
    priority                    varchar(32) NOT NULL DEFAULT 'NORMAL',
    estimated_prep_time_minutes int NOT NULL DEFAULT 15,
    version                     bigint NOT NULL DEFAULT 0,
    created_at                  timestamptz NOT NULL DEFAULT now(),
    updated_at                  timestamptz NOT NULL DEFAULT now(),
    completed_at                timestamptz,
    cancelled_at                timestamptz,
    cancelled_reason            varchar(255)
);
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders (status);
CREATE INDEX IF NOT EXISTS idx_orders_created_at ON orders (created_at);

CREATE TABLE IF NOT EXISTS order_items (
    id               uuid PRIMARY KEY,
    order_id         uuid NOT NULL,
    product_id       uuid NOT NULL,
    product_sku      varchar(255) NOT NULL,
    product_name     varchar(255) NOT NULL,
    quantity         int NOT NULL DEFAULT 1,
    unit_price_cents bigint NOT NULL DEFAULT 0,
    total_price_cents bigint NOT NULL DEFAULT 0,
    notes            varchar(255),
    status           varchar(32) NOT NULL DEFAULT 'PENDING',
    display_order    int NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_order_items_order ON order_items (order_id);

CREATE TABLE IF NOT EXISTS idempotency_keys (
    key             varchar(100) PRIMARY KEY,
    scope           varchar(50) NOT NULL,
    request_hash    varchar(255) NOT NULL,
    response_status int NOT NULL,
    response_body   text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);
