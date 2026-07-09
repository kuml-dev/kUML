-- ecommerce.sql — V3.4.9 test fixture mirroring the shape of
-- kuml-cli/src/test/resources/erm/valid-ecommerce.kuml.kts: exercises PK,
-- unique, nullable, default (literal + function-call), a named table-level
-- FK + CHECK, a composite-PK junction table with FKs added via
-- ALTER TABLE ADD CONSTRAINT (Flyway/pg_dump shape), CREATE INDEX
-- (simple + composite unique), and CREATE VIEW.

CREATE TABLE customers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE orders (
    id SERIAL PRIMARY KEY,
    customer_id UUID NOT NULL,
    total NUMERIC(10,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE CASCADE,
    CONSTRAINT chk_orders_total CHECK (total >= 0)
);

CREATE TABLE products (
    id SERIAL PRIMARY KEY,
    sku VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    price NUMERIC(10,2) NOT NULL
);

CREATE TABLE order_items (
    order_id INT NOT NULL,
    product_id INT NOT NULL,
    quantity INT NOT NULL DEFAULT 1,
    PRIMARY KEY (order_id, product_id)
);

ALTER TABLE order_items ADD CONSTRAINT fk_oi_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE;
ALTER TABLE order_items ADD CONSTRAINT fk_oi_product FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE RESTRICT;

CREATE INDEX idx_orders_customer ON orders (customer_id);
CREATE UNIQUE INDEX idx_orders_status_customer ON orders (customer_id, status);

CREATE VIEW big_orders AS SELECT * FROM orders WHERE total > 100;
