-- 1) Sequence for customers
CREATE SEQUENCE IF NOT EXISTS customers_seq
  START WITH 1
  INCREMENT BY 1
  NO MINVALUE
  NO MAXVALUE
  CACHE 1;

-- 2) Customers table
CREATE TABLE IF NOT EXISTS customers (
    id          BIGINT PRIMARY KEY DEFAULT nextval('customers_seq'),
    first_name  VARCHAR(100)   NOT NULL,
    last_name   VARCHAR(100)   NOT NULL,
    email       VARCHAR(255)   NOT NULL UNIQUE,
    phone       VARCHAR(50),
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT now()
    );

-- 3) Ensure customer_id column in orders is BIGINT and add FK
--    If your V1 defined orders.customer_id as BIGINT already, skip the TYPE cast.
ALTER TABLE orders
-- ensure column type is BIGINT
ALTER COLUMN customer_id TYPE BIGINT USING customer_id::BIGINT,
  -- add the foreign-key constraint
  ADD CONSTRAINT fk_orders_customers
    FOREIGN KEY (customer_id)
    REFERENCES customers(id)
    ON DELETE RESTRICT;

-- 4) Create index on orders(customer_id)
CREATE INDEX IF NOT EXISTS idx_orders_customer_id ON orders(customer_id);
