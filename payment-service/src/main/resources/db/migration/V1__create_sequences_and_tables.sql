-- sequence for payments
CREATE SEQUENCE IF NOT EXISTS payments_seq
  START WITH 1
  INCREMENT BY 1
  NO MINVALUE
  NO MAXVALUE
  CACHE 1;

-- payments table
CREATE TABLE IF NOT EXISTS payments (
    id             BIGINT PRIMARY KEY DEFAULT nextval('payments_seq'),
    order_id       BIGINT NOT NULL,
    amount         NUMERIC(19,2) NOT NULL,
    status         VARCHAR(50)   NOT NULL,
    payment_method VARCHAR(100),
    processed_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
    );
