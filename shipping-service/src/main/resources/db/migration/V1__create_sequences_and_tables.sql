-- sequence for shipments
CREATE SEQUENCE IF NOT EXISTS shipments_seq
  START WITH 1
  INCREMENT BY 1
  NO MINVALUE
  NO MAXVALUE
  CACHE 1;

-- shipments table
CREATE TABLE IF NOT EXISTS shipments (
    id           BIGINT PRIMARY KEY DEFAULT nextval('shipments_seq'),
    order_id     BIGINT NOT NULL,
    address      JSONB  NOT NULL,
    status       VARCHAR(50) NOT NULL,
    scheduled_at TIMESTAMPTZ,
    shipped_at   TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ
);
