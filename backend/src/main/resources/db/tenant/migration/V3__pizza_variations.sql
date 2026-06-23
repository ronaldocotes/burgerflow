CREATE TABLE product_sizes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    name VARCHAR(50) NOT NULL,
    code VARCHAR(5) NOT NULL,
    price_cents BIGINT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true,
    display_order INT NOT NULL DEFAULT 0
);

CREATE TABLE product_flavors (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    active BOOLEAN NOT NULL DEFAULT true,
    display_order INT NOT NULL DEFAULT 0
);

ALTER TABLE order_items
    ADD COLUMN size_id UUID,
    ADD COLUMN size_name VARCHAR(50),
    ADD COLUMN flavor1_id UUID,
    ADD COLUMN flavor1_name VARCHAR(100),
    ADD COLUMN flavor2_id UUID,
    ADD COLUMN flavor2_name VARCHAR(100),
    ADD COLUMN crust_type VARCHAR(30),
    ADD COLUMN dough_type VARCHAR(30);
