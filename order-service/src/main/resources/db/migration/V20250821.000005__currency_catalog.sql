CREATE TABLE IF NOT EXISTS currencies (
                                          code           CHAR(3)    PRIMARY KEY,  -- 'USD'
    numeric_code   SMALLINT   UNIQUE,       -- 840
    fraction_digits SMALLINT  NOT NULL,     -- 0..3 (ISO default, not cash-specific)
    name           TEXT       NOT NULL,
    active         BOOLEAN    NOT NULL DEFAULT TRUE
    );

-- minimal seed (extend as needed)
INSERT INTO currencies (code, numeric_code, fraction_digits, name) VALUES
    ('USD', 840, 2, 'US Dollar')        ON CONFLICT DO NOTHING,
  ('EUR', 978, 2, 'Euro')             ON CONFLICT DO NOTHING,
    ('PLN', 985, 2, 'Polish Złoty')     ON CONFLICT DO NOTHING,
    ('JPY', 392, 0, 'Japanese Yen')     ON CONFLICT DO NOTHING,
    ('KWD', 414, 3, 'Kuwaiti Dinar')    ON CONFLICT DO NOTHING;

-- V20250821_02__orders_money_minor.sql
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS currency_code CHAR(3) NOT NULL DEFAULT 'USD',
    ADD COLUMN IF NOT EXISTS total_amount_minor BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT fk_orders_currency FOREIGN KEY (currency_code) REFERENCES currencies(code),
    ADD CONSTRAINT chk_orders_amount_minor_nonneg CHECK (total_amount_minor >= 0);

-- backfill minor amounts using ISO fraction digits
UPDATE orders o
SET total_amount_minor = ROUND(
        (o.total_amount::numeric) * (10 ^ c.fraction_digits)
                         )::bigint
FROM currencies c
WHERE c.code = o.currency_code;
