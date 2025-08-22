-- orders: pricing columns
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS subtotal_amount NUMERIC(19,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS discount_code   VARCHAR(64),
    ADD COLUMN IF NOT EXISTS discount_amount NUMERIC(19,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS shipping_option VARCHAR(32),
    ADD COLUMN IF NOT EXISTS shipping_fee    NUMERIC(19,2) NOT NULL DEFAULT 0;

-- order_items: color + specs
ALTER TABLE order_items
    ADD COLUMN IF NOT EXISTS attributes_json JSONB;

-- optional, simple built-in promo codes
CREATE TABLE IF NOT EXISTS discount_codes (
    code         VARCHAR(64) PRIMARY KEY,
    type         VARCHAR(16) NOT NULL,         -- 'PERCENT' or 'AMOUNT'
    value        NUMERIC(19,2) NOT NULL,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    starts_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ  NOT NULL DEFAULT (now() + interval '5 years'),
    max_uses     INT,
    used_count   INT          NOT NULL DEFAULT 0
);

-- seed a couple
INSERT INTO discount_codes(code, type, value)
VALUES ('WELCOME10', 'PERCENT', 10),
       ('SAVE5',     'AMOUNT',  5)
    ON CONFLICT (code) DO NOTHING;
