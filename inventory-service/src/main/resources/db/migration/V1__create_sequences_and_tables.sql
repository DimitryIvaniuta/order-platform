-- sequence for inventory_reservations
CREATE SEQUENCE IF NOT EXISTS inventory_reservations_seq
  START WITH 1
  INCREMENT BY 1
  NO MINVALUE
  NO MAXVALUE
  CACHE 1;

-- inventory_reservations table
CREATE TABLE IF NOT EXISTS inventory_reservations (
    id             BIGINT PRIMARY KEY DEFAULT nextval('inventory_reservations_seq'),
    order_id       BIGINT NOT NULL,
    order_item_id  BIGINT NOT NULL,
    vendor_id      UUID  NOT NULL,
    quantity       INTEGER NOT NULL,
    status         VARCHAR(50) NOT NULL,
    reserved_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
