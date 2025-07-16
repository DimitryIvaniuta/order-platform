-- create a sequence for orders
CREATE SEQUENCE IF NOT EXISTS orders_seq
  START WITH 1
  INCREMENT BY 1
  NO MINVALUE
  NO MAXVALUE
  CACHE 1;

-- create a sequence for order_items
CREATE SEQUENCE IF NOT EXISTS order_items_seq
  START WITH 1
  INCREMENT BY 1
  NO MINVALUE
  NO MAXVALUE
  CACHE 1;

-- orders table
CREATE TABLE IF NOT EXISTS orders (
    id            BIGINT PRIMARY KEY DEFAULT nextval('orders_seq'),
    customer_id   UUID    NOT NULL,
    total_amount  NUMERIC(19,2) NOT NULL,
    status        VARCHAR(50)   NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
    );

-- order_items table
CREATE TABLE IF NOT EXISTS order_items (
    id         BIGINT   PRIMARY KEY DEFAULT nextval('order_items_seq'),
    order_id   BIGINT   NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id UUID     NOT NULL,
    vendor_id  UUID     NOT NULL,
    quantity   INTEGER  NOT NULL,
    price      NUMERIC(19,2) NOT NULL
    );